package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.mcp.McpTokenDtos;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.mcp.McpTokenService;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mint, list and revoke the MCP bearer credentials of ADR 0041 §3.
 *
 * <p>ADR 0041 says a user authenticated in the web application mints the token for an MCP client,
 * so without these three routes the MCP server is designed but unusable. They live on the ordinary
 * JWT chain, one path segment below the MCP endpoint but deliberately outside its security matcher:
 * an MCP token cannot reach them, so it cannot mint a successor for itself or revoke the one that
 * would stop it.
 *
 * <p><b>Authorization is "self", not an IAM action.</b> Every route here acts only on the caller's
 * own credentials, and the credential it produces grants strictly what that user already holds —
 * every tool call re-evaluates the same policies against the same principal. Gating it behind a new
 * IAM action would have added MCP-specific permission vocabulary, which ADR 0041 §2 rules out, and
 * would have meant an administrator can grant "may create a token" separately from "may read
 * meetings", which is a distinction with nothing behind it. Same reasoning, same annotation, as the
 * account and session routes in {@code AuthController}.
 */
@RestController
@RequestMapping("/mcp/tokens")
public class McpTokensController {

    private static final int MAX_EXPIRY_DAYS = 365;

    private final McpTokenService tokens;
    private final Clock clock;

    public McpTokensController(McpTokenService tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Mints a token and returns its plaintext, for the only time it will ever be readable. Nothing
     * persists it, so a token that is not copied out of this response is a token that has to be
     * revoked and replaced.
     */
    @PostMapping
    @AuthorizationNotRequired(reason = "Self: mints a credential for the caller's own principal.")
    public ResponseEntity<McpTokenDtos.CreatedResponse> create(
            @RequestBody McpTokenDtos.CreateRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        McpTokenService.MintedToken minted =
                tokens.mint(
                        principal.tenantId(),
                        principal.userId(),
                        body == null ? null : body.name(),
                        expiry(body));
        McpTokenDtos.CreatedResponse response =
                McpTokenDtos.CreatedResponse.from(minted.token(), minted.plaintext());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** The caller's own tokens, newest first. Revoked ones stay listed, flagged inactive. */
    @GetMapping
    @AuthorizationNotRequired(reason = "Self: lists only the caller's own credentials.")
    public McpTokenDtos.TokenListResponse list() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        Instant now = clock.now();
        List<McpTokenDtos.TokenSummary> items =
                tokens.list(principal.tenantId(), principal.userId()).stream()
                        .map(t -> McpTokenDtos.TokenSummary.from(t, now))
                        .toList();
        return new McpTokenDtos.TokenListResponse(items);
    }

    /** Revokes one of the caller's own tokens. Idempotent; an unknown id is 404. */
    @DeleteMapping("/{id}")
    @AuthorizationNotRequired(reason = "Self: revokes only the caller's own credentials.")
    public ResponseEntity<Void> revoke(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        tokens.revoke(id, principal.tenantId(), principal.userId());
        return ResponseEntity.noContent().build();
    }

    /**
     * A null or absent {@code expiresInDays} means no hard expiry, which is what an MCP client in a
     * configuration file needs; revocation remains the kill switch either way. Values outside
     * 1..365 are clamped rather than refused — the field is a convenience, not a security control.
     */
    private static Duration expiry(McpTokenDtos.CreateRequest body) {
        if (body == null || body.expiresInDays() == null) {
            return null;
        }
        int days = Math.min(MAX_EXPIRY_DAYS, Math.max(1, body.expiresInDays()));
        return Duration.ofDays(days);
    }
}

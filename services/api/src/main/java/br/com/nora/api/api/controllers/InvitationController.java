package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.auth.LoginResponse;
import br.com.nora.api.api.dto.iam.AcceptInviteRequest;
import br.com.nora.api.api.dto.iam.InviteListResponse;
import br.com.nora.api.api.dto.iam.InviteResponse;
import br.com.nora.api.api.dto.iam.InviteUserRequest;
import br.com.nora.api.api.security.AuthCookies;
import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.iam.InvitationService;
import br.com.nora.api.application.iam.InvitationService.AcceptResult;
import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * E-mail invitation endpoints (US06, ADR 0011).
 *
 * <ul>
 *   <li>{@code POST /iam/users/invite} — creates an invite, requires IAM {@code iam:user:invite}.
 *   <li>{@code POST /iam/invites/{token}/accept} — public; creates the user and returns a JWT.
 *   <li>{@code GET /iam/invites} — lists the tenant's invites; requires {@code iam:invite:read}.
 *   <li>{@code DELETE /iam/invites/{id}} — revokes PENDING; requires {@code iam:invite:revoke}.
 * </ul>
 *
 * <p>The accept endpoint is public because the {@code token} is the credential; whoever holds the
 * token proves they have access to the recipient e-mail. {@code SecurityConfig} opens the path in
 * {@code PUBLIC_ENDPOINTS}.
 */
@RestController
@RequestMapping("/iam")
public class InvitationController {

    private final InvitationService service;
    private final AuthCookies cookies;

    public InvitationController(InvitationService service, AuthCookies cookies) {
        this.service = service;
        this.cookies = cookies;
    }

    @PostMapping("/users/invite")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(action = "iam:user:invite", resource = ResourceType.INVITE)
    public InviteResponse invite(@Valid @RequestBody InviteUserRequest body) {
        AuthenticatedPrincipal p = CurrentUser.require();
        var groupIds =
                body.groupIds() == null
                        ? java.util.Set.<UUID>of()
                        : new LinkedHashSet<>(body.groupIds());
        IamInvitation invite =
                service.inviteUser(
                        p.tenantId(), p.userId(), body.email(), groupIds, body.expiresInDays());
        return toResponse(invite);
    }

    @PostMapping("/invites/{token}/accept")
    @AuthorizationNotRequired(reason = "Public: the invite token is the credential (US06).")
    public ResponseEntity<LoginResponse> accept(
            @PathVariable("token") String token,
            @Valid @RequestBody AcceptInviteRequest body,
            HttpServletRequest httpReq) {
        AcceptResult result = service.acceptInvite(token, body.displayName(), body.password());
        // Same rule as the login: web client (X-NORA-Client: web header) gets the session only by
        // httpOnly cookie; tokens stay out of the body. Native client (no header) gets them in the
        // body.
        boolean nativeClient = !"web".equalsIgnoreCase(httpReq.getHeader("X-NORA-Client"));
        LoginResponse resp =
                new LoginResponse(
                        nativeClient ? result.accessToken() : null,
                        nativeClient ? result.refreshTokenPlain() : null,
                        "Bearer",
                        result.expiresInSeconds(),
                        result.user().id(),
                        result.user().tenantId(),
                        result.user().email().value(),
                        result.user().displayName());
        HttpHeaders headers = new HttpHeaders();
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildAccessCookie(
                        result.accessToken(), Duration.ofSeconds(result.expiresInSeconds())));
        AuthCookies.appendSetCookie(
                headers,
                cookies.buildRefreshCookie(
                        result.refreshTokenPlain(),
                        Duration.ofSeconds(result.refreshExpiresInSeconds())));
        return ResponseEntity.ok().headers(headers).body(resp);
    }

    @GetMapping("/invites")
    @RequiresPermission(action = "iam:invite:read", resource = ResourceType.INVITE)
    public InviteListResponse list(
            @RequestParam(name = "status", required = false) InvitationStatus status) {
        AuthenticatedPrincipal p = CurrentUser.require();
        List<InviteResponse> items =
                service.listInvites(p.tenantId(), status).stream()
                        .map(InvitationController::toResponse)
                        .toList();
        return new InviteListResponse(items, items.size(), 1, items.size());
    }

    @DeleteMapping("/invites/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(
            action = "iam:invite:revoke",
            resource = ResourceType.INVITE,
            idParam = "id")
    public void revoke(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        service.revokeInvite(id, p.tenantId(), p.userId());
    }

    // ---------- mapping ----------

    private static InviteResponse toResponse(IamInvitation inv) {
        return new InviteResponse(
                inv.id(),
                inv.tenantId(),
                inv.email(),
                inv.status().name(),
                inv.invitedBy(),
                OffsetDateTime.ofInstant(inv.invitedAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(inv.expiresAt(), ZoneOffset.UTC),
                List.copyOf(inv.groupIds()),
                inv.acceptedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(inv.acceptedAt(), ZoneOffset.UTC),
                inv.acceptedUserId());
    }
}

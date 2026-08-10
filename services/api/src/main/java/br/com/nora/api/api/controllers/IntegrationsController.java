package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
import br.com.nora.api.application.integration.TelegramPairingService;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
 * OAuth integrations hub (NORA Flows Phase 2). The callback is PUBLIC by design (provider redirect;
 * the signed state identifies tenant/user and blocks forgery) and always REDIRECTS to the front end
 * (/integrations) with a success/error query — it never returns JSON to the user's browser.
 *
 * <p>Every other handler is gated by IAM (#51): a connection is tenant-wide, so connecting,
 * disconnecting or pairing a provider changes what the whole workspace's automations can reach, and
 * the status listing enumerates which external accounts the tenant is wired to. Reading the hub
 * takes {@code integration:read}; anything that mutates a connection takes {@code
 * integration:write}. The resource is the tenant's integrations scope, {@code
 * nora:tenant/{t}:integration/*}.
 */
@RestController
@RequestMapping("/integrations")
public class IntegrationsController {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationsController.class);

    private final IntegrationService integrations;
    private final TelegramPairingService telegramPairing;
    private final String frontendBaseUrl;

    public IntegrationsController(
            IntegrationService integrations,
            TelegramPairingService telegramPairing,
            @Value("${nora.frontend.base-url}") String frontendBaseUrl) {
        this.integrations = integrations;
        this.telegramPairing = telegramPairing;
        this.frontendBaseUrl =
                frontendBaseUrl.endsWith("/")
                        ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                        : frontendBaseUrl;
    }

    @GetMapping
    @RequiresPermission(action = "integration:read", resource = ResourceType.INTEGRATION)
    public List<ProviderStatus> status() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return integrations.status(principal.tenantId());
    }

    /** Starts the OAuth flow: returns the authorization URL for the front end to redirect to. */
    @PostMapping("/{provider}/oauth/start")
    @RequiresPermission(action = "integration:write", resource = ResourceType.INTEGRATION)
    public ResponseEntity<StartResponse> start(@PathVariable("provider") String provider) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        IntegrationProvider parsed;
        try {
            parsed = IntegrationProvider.fromWire(provider);
        } catch (IllegalArgumentException ex) {
            throw new IntegrationException.UnknownProvider(provider);
        }
        String url = integrations.start(parsed, principal.tenantId(), principal.userId());
        return ResponseEntity.ok(new StartResponse(url));
    }

    /**
     * OAuth callback for ANY provider (public; browser redirect — the SecurityConfig wildcard
     * "integrations/&#42;/oauth/callback" covers them all). Always redirects to the front end; the
     * service routes Google/Slack to the dedicated flows and the rest to the generic one.
     */
    @GetMapping("/{provider}/oauth/callback")
    @AuthorizationNotRequired(reason = "Public: the signed OAuth state is the credential.")
    public ResponseEntity<Void> oauthCallback(
            @PathVariable("provider") String provider,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error) {
        IntegrationProvider parsed;
        try {
            parsed = IntegrationProvider.fromWire(provider);
        } catch (IllegalArgumentException ex) {
            return redirect("/integrations?error=integration_unknown_provider");
        }
        if (error != null && !error.isBlank()) {
            // User denied consent (or provider error) — no stack, no 500.
            return redirect("/integrations?error=" + error);
        }
        if (code == null || code.isBlank()) {
            return redirect("/integrations?error=missing_code");
        }
        try {
            integrations.handleCallback(parsed, code, state);
            return redirect("/integrations?connected=" + parsed.wire());
        } catch (IntegrationException ex) {
            LOG.warn("OAuth {} callback falhou: {} {}", parsed.wire(), ex.code(), ex.getMessage());
            return redirect("/integrations?error=" + ex.code().toLowerCase());
        } catch (RuntimeException ex) {
            LOG.error("OAuth {} callback erro inesperado", parsed.wire(), ex);
            return redirect("/integrations?error=internal");
        }
    }

    @DeleteMapping("/{provider}")
    @RequiresPermission(action = "integration:write", resource = ResourceType.INTEGRATION)
    public ResponseEntity<Void> disconnect(@PathVariable("provider") String provider) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        integrations.disconnect(principal.tenantId(), provider);
        return ResponseEntity.noContent().build();
    }

    /**
     * Telegram (wave 2, NO OAuth): generates the tenant's pairing code and returns the bot deep
     * link ({@code t.me/<bot>?start=<code>}) for the hub to display.
     */
    @PostMapping("/telegram/pairing/start")
    @RequiresPermission(action = "integration:write", resource = ResourceType.INTEGRATION)
    public TelegramPairingService.PairingStart telegramPairingStart() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return telegramPairing.start(principal.tenantId(), principal.userId());
    }

    /**
     * Telegram: looks for the tenant's {@code /start <code>} in the bot's getUpdates and
     * completes the connection. No /start yet = 409 {@code INTEGRATION_PAIRING_PENDING} with an
     * actionable message.
     */
    @PostMapping("/telegram/pairing/verify")
    @RequiresPermission(action = "integration:write", resource = ResourceType.INTEGRATION)
    public ProviderStatus telegramPairingVerify() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return telegramPairing.verify(principal.tenantId());
    }

    /**
     * Trello (wave 2, no server-side OAuth): validates the token the user pasted and persists the
     * connection. Invalid token = 502 {@code INTEGRATION_PROVIDER_ERROR} with guidance.
     */
    @PostMapping("/trello/token")
    @RequiresPermission(action = "integration:write", resource = ResourceType.INTEGRATION)
    public ProviderStatus saveTrelloToken(@RequestBody TrelloTokenRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return integrations.saveTrelloToken(
                principal.tenantId(), principal.userId(), body == null ? null : body.token());
    }

    public record StartResponse(String authorizeUrl) {}

    public record TrelloTokenRequest(String token) {}

    private ResponseEntity<Void> redirect(String frontendPath) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + frontendPath))
                .build();
    }
}

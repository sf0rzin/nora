package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hub de integrações OAuth (NORA Flows Fase 2). O callback é PÚBLICO por design (redirect do
 * provedor; o state assinado identifica tenant/usuário e bloqueia forge) e sempre REDIRECIONA pro
 * front (/integracoes) com query de sucesso/erro — nunca devolve JSON pro navegador do usuário.
 */
@RestController
@RequestMapping("/integrations")
public class IntegrationsController {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationsController.class);

    private final IntegrationService integrations;
    private final String frontendBaseUrl;

    public IntegrationsController(
            IntegrationService integrations,
            @Value("${nora.frontend.base-url}") String frontendBaseUrl) {
        this.integrations = integrations;
        this.frontendBaseUrl =
                frontendBaseUrl.endsWith("/")
                        ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                        : frontendBaseUrl;
    }

    @GetMapping
    public List<ProviderStatus> status() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return integrations.status(principal.tenantId());
    }

    /** Inicia o fluxo OAuth: devolve a URL de autorização pro front redirecionar. */
    @PostMapping("/{provider}/oauth/start")
    public ResponseEntity<StartResponse> start(@PathVariable("provider") String provider) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        if (!IntegrationProvider.GOOGLE.wire().equalsIgnoreCase(provider)) {
            throw new IntegrationException.UnknownProvider(provider);
        }
        String url = integrations.startGoogle(principal.tenantId(), principal.userId());
        return ResponseEntity.ok(new StartResponse(url));
    }

    /** Callback do Google (público; redirect do navegador). Sempre redireciona pro front. */
    @GetMapping("/google/oauth/callback")
    public ResponseEntity<Void> googleCallback(
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error) {
        if (error != null && !error.isBlank()) {
            // Usuário negou o consentimento (ou erro do Google) — sem stack, sem 500.
            return redirect("/integracoes?error=" + error);
        }
        if (code == null || code.isBlank()) {
            return redirect("/integracoes?error=missing_code");
        }
        try {
            integrations.handleGoogleCallback(code, state);
            return redirect("/integracoes?connected=google");
        } catch (IntegrationException ex) {
            LOG.warn("OAuth Google callback falhou: {} {}", ex.code(), ex.getMessage());
            return redirect("/integracoes?error=" + ex.code().toLowerCase());
        } catch (RuntimeException ex) {
            LOG.error("OAuth Google callback erro inesperado", ex);
            return redirect("/integracoes?error=internal");
        }
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(@PathVariable("provider") String provider) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        integrations.disconnect(principal.tenantId(), provider);
        return ResponseEntity.noContent().build();
    }

    public record StartResponse(String authorizeUrl) {}

    private ResponseEntity<Void> redirect(String frontendPath) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(frontendBaseUrl + frontendPath))
                .build();
    }
}

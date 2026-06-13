package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.CurrentUser;
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
 * Hub de integrações OAuth (NORA Flows Fase 2). O callback é PÚBLICO por design (redirect do
 * provedor; o state assinado identifica tenant/usuário e bloqueia forge) e sempre REDIRECIONA pro
 * front (/integracoes) com query de sucesso/erro — nunca devolve JSON pro navegador do usuário.
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
    public List<ProviderStatus> status() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return integrations.status(principal.tenantId());
    }

    /** Inicia o fluxo OAuth: devolve a URL de autorização pro front redirecionar. */
    @PostMapping("/{provider}/oauth/start")
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
     * Callback OAuth de QUALQUER provedor (público; redirect do navegador — o wildcard do
     * SecurityConfig "integrations/&#42;/oauth/callback" cobre todos). Sempre redireciona pro
     * front; o service roteia Google/Slack pros fluxos dedicados e os demais pro genérico.
     */
    @GetMapping("/{provider}/oauth/callback")
    public ResponseEntity<Void> oauthCallback(
            @PathVariable("provider") String provider,
            @RequestParam(name = "code", required = false) String code,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "error", required = false) String error) {
        IntegrationProvider parsed;
        try {
            parsed = IntegrationProvider.fromWire(provider);
        } catch (IllegalArgumentException ex) {
            return redirect("/integracoes?error=integration_unknown_provider");
        }
        if (error != null && !error.isBlank()) {
            // Usuário negou o consentimento (ou erro do provedor) — sem stack, sem 500.
            return redirect("/integracoes?error=" + error);
        }
        if (code == null || code.isBlank()) {
            return redirect("/integracoes?error=missing_code");
        }
        try {
            integrations.handleCallback(parsed, code, state);
            return redirect("/integracoes?connected=" + parsed.wire());
        } catch (IntegrationException ex) {
            LOG.warn("OAuth {} callback falhou: {} {}", parsed.wire(), ex.code(), ex.getMessage());
            return redirect("/integracoes?error=" + ex.code().toLowerCase());
        } catch (RuntimeException ex) {
            LOG.error("OAuth {} callback erro inesperado", parsed.wire(), ex);
            return redirect("/integracoes?error=internal");
        }
    }

    @DeleteMapping("/{provider}")
    public ResponseEntity<Void> disconnect(@PathVariable("provider") String provider) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        integrations.disconnect(principal.tenantId(), provider);
        return ResponseEntity.noContent().build();
    }

    /**
     * Telegram (onda 2, SEM OAuth): gera o código de pareamento do tenant e devolve o deep link do
     * bot ({@code t.me/<bot>?start=<código>}) pro hub exibir.
     */
    @PostMapping("/telegram/pairing/start")
    public TelegramPairingService.PairingStart telegramPairingStart() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return telegramPairing.start(principal.tenantId(), principal.userId());
    }

    /**
     * Telegram: procura o {@code /start <código>} do tenant no getUpdates do bot e conclui a
     * conexão. Sem o /start ainda = 409 {@code INTEGRATION_PAIRING_PENDING} com mensagem acionável.
     */
    @PostMapping("/telegram/pairing/verify")
    public ProviderStatus telegramPairingVerify() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return telegramPairing.verify(principal.tenantId());
    }

    /**
     * Trello (onda 2, sem OAuth server-side): valida o token que o usuário colou e persiste a
     * conexão. Token inválido = 502 {@code INTEGRATION_PROVIDER_ERROR} com orientação.
     */
    @PostMapping("/trello/token")
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

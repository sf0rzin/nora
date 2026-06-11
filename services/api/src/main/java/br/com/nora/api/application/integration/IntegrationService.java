package br.com.nora.api.application.integration;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import br.com.nora.api.application.ports.GoogleOAuthClient.TokenResponse;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.SlackOAuthClient;
import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hub de integrações OAuth (NORA Flows Fase 2). Fluxo comum aos provedores: {@code start*} monta a
 * URL de autorização com state assinado → usuário consente no provedor → {@code handle*Callback}
 * troca o code por tokens, identifica a conta e faz upsert da conexão do tenant.
 *
 * <p>Google: {@code validGoogleAccessToken} é o que as ações (Gmail/Calendar) usam em runtime —
 * renova via refresh token quando expirado (rotation persistida). Escopos: gmail.send,
 * calendar.events e openid email.
 *
 * <p>Slack: {@code validSlackBotToken} devolve o bot token ({@code xoxb-...}) — NÃO expira, sem
 * refresh. Escopos do bot: chat:write (postar) e channels:read (listar canais). Tudo tenant-scoped
 * (ADR 0002 + RLS).
 */
@Service
public class IntegrationService {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationService.class);

    private static final String GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_SCOPES =
            "openid email https://www.googleapis.com/auth/gmail.send"
                    + " https://www.googleapis.com/auth/calendar.events";

    private static final String SLACK_AUTH_URL = "https://slack.com/oauth/v2/authorize";
    private static final String SLACK_SCOPES = "chat:write,channels:read";

    /** Margem antes do expiresAt para renovar proativamente (evita 401 em voo). */
    private static final long REFRESH_SKEW_SECONDS = 60;

    private final IntegrationConnectionRepository connections;
    private final GoogleOAuthClient google;
    private final SlackOAuthClient slack;
    private final OAuthStateCodec stateCodec;
    private final Clock clock;
    private final String googleClientId;
    private final String googleRedirectUri;
    private final String slackClientId;
    private final String slackRedirectUri;

    public IntegrationService(
            IntegrationConnectionRepository connections,
            GoogleOAuthClient google,
            SlackOAuthClient slack,
            OAuthStateCodec stateCodec,
            Clock clock,
            @Value("${nora.integrations.google.client-id:}") String googleClientId,
            @Value("${nora.integrations.google.redirect-uri:}") String googleRedirectUri,
            @Value("${nora.integrations.slack.client-id:}") String slackClientId,
            @Value("${nora.integrations.slack.redirect-uri:}") String slackRedirectUri) {
        this.connections = connections;
        this.google = google;
        this.slack = slack;
        this.stateCodec = stateCodec;
        this.clock = clock;
        this.googleClientId = googleClientId;
        this.googleRedirectUri = googleRedirectUri;
        this.slackClientId = slackClientId;
        this.slackRedirectUri = slackRedirectUri;
    }

    /** Status de cada provedor para o hub (conectado? qual conta?). */
    @Transactional(readOnly = true)
    public List<ProviderStatus> status(UUID tenantId) {
        List<IntegrationConnection> existing = connections.listByTenant(tenantId);
        return List.of(IntegrationProvider.values()).stream()
                .map(
                        provider -> {
                            Optional<IntegrationConnection> conn =
                                    existing.stream()
                                            .filter(c -> c.provider() == provider)
                                            .findFirst();
                            boolean configured = configured(provider);
                            return new ProviderStatus(
                                    provider.wire(),
                                    configured,
                                    conn.isPresent(),
                                    conn.map(IntegrationConnection::externalAccount).orElse(null),
                                    conn.map(IntegrationConnection::updatedAt).orElse(null));
                        })
                .toList();
    }

    /** Monta a URL de autorização do Google com state assinado (tenant/usuário embutidos). */
    public String startGoogle(UUID tenantId, UUID userId) {
        requireGoogleConfigured();
        String state = stateCodec.encode(tenantId, userId, IntegrationProvider.GOOGLE, clock.now());
        return GOOGLE_AUTH_URL
                + "?client_id="
                + url(googleClientId)
                + "&redirect_uri="
                + url(googleRedirectUri)
                + "&response_type=code"
                + "&scope="
                + url(GOOGLE_SCOPES)
                + "&access_type=offline"
                + "&prompt=consent"
                + "&include_granted_scopes=true"
                + "&state="
                + url(state);
    }

    /**
     * Callback do Google: valida o state, troca o code por tokens e faz upsert da conexão. Retorna
     * o tenant/usuário do state (o controller redireciona pro front).
     */
    @Transactional
    public OAuthStateCodec.DecodedState handleGoogleCallback(String code, String state) {
        requireGoogleConfigured();
        OAuthStateCodec.DecodedState decoded = stateCodec.decode(state, clock.now());
        if (decoded.provider() != IntegrationProvider.GOOGLE) {
            throw new IntegrationException.InvalidState();
        }
        TokenResponse tokens = google.exchangeCode(code, googleRedirectUri);
        String account = safeUserEmail(tokens.accessToken());
        OffsetDateTime now = now();
        connections.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        decoded.tenantId(),
                        decoded.userId(),
                        IntegrationProvider.GOOGLE,
                        tokens.scope() == null ? GOOGLE_SCOPES : tokens.scope(),
                        account,
                        tokens.accessToken(),
                        tokens.refreshToken(),
                        now.plusSeconds(tokens.expiresInSeconds()),
                        now,
                        now));
        return decoded;
    }

    @Transactional
    public void disconnect(UUID tenantId, String providerWire) {
        IntegrationProvider provider = parseProvider(providerWire);
        connections.delete(tenantId, provider);
    }

    /**
     * Access token Google VÁLIDO para uso imediato pelas ações do Flows. Renova (e persiste a
     * rotation) quando expirado/perto de expirar. Lança {@code NotConnected} sem conexão.
     */
    @Transactional
    public String validGoogleAccessToken(UUID tenantId) {
        IntegrationConnection conn =
                connections
                        .findByTenantAndProvider(tenantId, IntegrationProvider.GOOGLE)
                        .orElseThrow(
                                () ->
                                        new IntegrationException.NotConnected(
                                                IntegrationProvider.GOOGLE.wire()));
        OffsetDateTime now = now();
        if (conn.expiresAt() != null
                && now.plusSeconds(REFRESH_SKEW_SECONDS).isBefore(conn.expiresAt())) {
            return conn.accessToken();
        }
        if (conn.refreshToken() == null || conn.refreshToken().isBlank()) {
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.GOOGLE.wire(),
                    "token expirado e sem refresh token — reconecte a integração");
        }
        TokenResponse refreshed = google.refresh(conn.refreshToken());
        IntegrationConnection updated =
                conn.withTokens(
                        refreshed.accessToken(),
                        refreshed.refreshToken(),
                        now.plusSeconds(refreshed.expiresInSeconds()),
                        now);
        connections.updateTokens(updated);
        return updated.accessToken();
    }

    /** Monta a URL de autorização do Slack (OAuth v2) com state assinado. */
    public String startSlack(UUID tenantId, UUID userId) {
        requireSlackConfigured();
        String state = stateCodec.encode(tenantId, userId, IntegrationProvider.SLACK, clock.now());
        return SLACK_AUTH_URL
                + "?client_id="
                + url(slackClientId)
                + "&scope="
                + url(SLACK_SCOPES)
                + "&redirect_uri="
                + url(slackRedirectUri)
                + "&state="
                + url(state);
    }

    /**
     * Callback do Slack: valida o state, troca o code pelo bot token e faz upsert da conexão. Bot
     * token não expira — refreshToken/expiresAt ficam nulos por contrato; a workspace conectada
     * (team name) vira a conta externa exibida no hub.
     */
    @Transactional
    public OAuthStateCodec.DecodedState handleSlackCallback(String code, String state) {
        requireSlackConfigured();
        OAuthStateCodec.DecodedState decoded = stateCodec.decode(state, clock.now());
        if (decoded.provider() != IntegrationProvider.SLACK) {
            throw new IntegrationException.InvalidState();
        }
        SlackOAuthClient.TokenResponse tokens = slack.exchangeCode(code, slackRedirectUri);
        OffsetDateTime now = now();
        connections.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        decoded.tenantId(),
                        decoded.userId(),
                        IntegrationProvider.SLACK,
                        tokens.scope() == null ? SLACK_SCOPES : tokens.scope(),
                        tokens.teamName(),
                        tokens.botToken(),
                        null,
                        null,
                        now,
                        now));
        return decoded;
    }

    /**
     * Bot token Slack para uso imediato pelas ações do Flows. Sem refresh (o token não expira):
     * devolve o token persistido ou lança {@code NotConnected}.
     */
    @Transactional(readOnly = true)
    public String validSlackBotToken(UUID tenantId) {
        return connections
                .findByTenantAndProvider(tenantId, IntegrationProvider.SLACK)
                .orElseThrow(
                        () ->
                                new IntegrationException.NotConnected(
                                        IntegrationProvider.SLACK.wire()))
                .accessToken();
    }

    public record ProviderStatus(
            String provider,
            boolean configured,
            boolean connected,
            String externalAccount,
            OffsetDateTime connectedAt) {}

    private boolean configured(IntegrationProvider provider) {
        return switch (provider) {
            case GOOGLE -> googleConfigured();
            case SLACK -> slackConfigured();
        };
    }

    private boolean googleConfigured() {
        return googleClientId != null
                && !googleClientId.isBlank()
                && googleRedirectUri != null
                && !googleRedirectUri.isBlank();
    }

    private boolean slackConfigured() {
        return slackClientId != null
                && !slackClientId.isBlank()
                && slackRedirectUri != null
                && !slackRedirectUri.isBlank();
    }

    private void requireGoogleConfigured() {
        if (!googleConfigured()) {
            throw new IntegrationException.NotConfigured(IntegrationProvider.GOOGLE.wire());
        }
    }

    private void requireSlackConfigured() {
        if (!slackConfigured()) {
            throw new IntegrationException.NotConfigured(IntegrationProvider.SLACK.wire());
        }
    }

    private IntegrationProvider parseProvider(String wire) {
        try {
            return IntegrationProvider.fromWire(wire);
        } catch (IllegalArgumentException ex) {
            throw new IntegrationException.UnknownProvider(wire);
        }
    }

    private String safeUserEmail(String accessToken) {
        try {
            return google.userEmail(accessToken);
        } catch (RuntimeException ex) {
            // Conta sem e-mail visível não impede a conexão — o hub mostra "Conectado".
            LOG.warn("Não consegui resolver o e-mail da conta Google: {}", ex.getMessage());
            return null;
        }
    }

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

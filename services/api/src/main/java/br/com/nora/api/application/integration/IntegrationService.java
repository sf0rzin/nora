package br.com.nora.api.application.integration;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.GenericOAuthClient;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import br.com.nora.api.application.ports.GoogleOAuthClient.TokenResponse;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.SlackOAuthClient;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.ports.TrelloApi;
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
 *
 * <p>Demais provedores OAuth (GitHub, Notion, Todoist, Linear — onda 1; Microsoft — onda 2): fluxo
 * OAuth2 code-flow padrão declarado no {@link OAuthProviderDirectory} e executado pelo client
 * genérico. {@code validAccessToken} devolve o token persistido; quando o config declara {@code
 * supportsRefresh} (Microsoft), renova com a mesma semântica do Google (skew 60s + rotation
 * persistida); senão, token expirado orienta reconexão.
 *
 * <p>Onda 2 fora do OAuth: Telegram conecta por pareamento de código (ver {@code
 * TelegramPairingService}) e Trello por token colado pelo usuário ({@link #saveTrelloToken}) — o
 * start do Trello devolve a URL de authorize do próprio Trello pra abrir em nova aba.
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
    private final GenericOAuthClient genericOAuth;
    private final OAuthProviderDirectory providerDirectory;
    private final TrelloApi trello;
    private final OAuthStateCodec stateCodec;
    private final Clock clock;
    private final TenantRlsContext rlsContext;
    private final String googleClientId;
    private final String googleRedirectUri;
    private final String slackClientId;
    private final String slackRedirectUri;
    private final String telegramBotToken;
    private final String trelloApiKey;

    public IntegrationService(
            IntegrationConnectionRepository connections,
            GoogleOAuthClient google,
            SlackOAuthClient slack,
            GenericOAuthClient genericOAuth,
            OAuthProviderDirectory providerDirectory,
            TrelloApi trello,
            OAuthStateCodec stateCodec,
            Clock clock,
            TenantRlsContext rlsContext,
            @Value("${nora.integrations.google.client-id:}") String googleClientId,
            @Value("${nora.integrations.google.redirect-uri:}") String googleRedirectUri,
            @Value("${nora.integrations.slack.client-id:}") String slackClientId,
            @Value("${nora.integrations.slack.redirect-uri:}") String slackRedirectUri,
            @Value("${nora.integrations.telegram.bot-token:}") String telegramBotToken,
            @Value("${nora.integrations.trello.api-key:}") String trelloApiKey) {
        this.connections = connections;
        this.google = google;
        this.slack = slack;
        this.genericOAuth = genericOAuth;
        this.providerDirectory = providerDirectory;
        this.trello = trello;
        this.stateCodec = stateCodec;
        this.clock = clock;
        this.rlsContext = rlsContext;
        this.googleClientId = googleClientId;
        this.googleRedirectUri = googleRedirectUri;
        this.slackClientId = slackClientId;
        this.slackRedirectUri = slackRedirectUri;
        this.telegramBotToken = telegramBotToken;
        this.trelloApiKey = trelloApiKey;
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

    /** Status de UM provedor (resposta dos endpoints de pareamento/token do hub). */
    @Transactional(readOnly = true)
    public ProviderStatus statusOf(UUID tenantId, IntegrationProvider provider) {
        Optional<IntegrationConnection> conn =
                connections.findByTenantAndProvider(tenantId, provider);
        return new ProviderStatus(
                provider.wire(),
                configured(provider),
                conn.isPresent(),
                conn.map(IntegrationConnection::externalAccount).orElse(null),
                conn.map(IntegrationConnection::updatedAt).orElse(null));
    }

    /**
     * Inicia a conexão do provedor. Google/Slack têm fluxos dedicados; Trello devolve a URL de
     * authorize do próprio Trello (o usuário copia o token gerado e cola no hub); Telegram NÃO
     * passa por aqui (pareamento por código — {@code TelegramPairingService}) e cai no genérico,
     * que falha {@code NotConfigured} por nunca estar no catálogo OAuth; o resto é genérico.
     */
    public String start(IntegrationProvider provider, UUID tenantId, UUID userId) {
        return switch (provider) {
            case GOOGLE -> startGoogle(tenantId, userId);
            case SLACK -> startSlack(tenantId, userId);
            case TRELLO -> startTrello();
            default -> startGeneric(provider, tenantId, userId);
        };
    }

    /** Conclui o callback OAuth do provedor (mesmo roteamento do {@link #start}). */
    @Transactional
    public OAuthStateCodec.DecodedState handleCallback(
            IntegrationProvider provider, String code, String state) {
        // `/integrations/*/oauth/callback` é público (SecurityConfig.PUBLIC_ENDPOINTS): chega por
        // redirect do provedor, sem JWT, então o JwtAuthenticationFilter não populou o contexto de
        // tenant que o TenantRlsAspect lê. V024 pôs RLS em integration_connections com
        // `WITH CHECK (tenant_id = nora.current_tenant_id())`, e V020 montou a lista de isenções
        // para fluxos sem JWT antes de V024 existir — esta tabela nunca entrou nela.
        //
        // Sem o GUC, com nora.security.rls.enforce=true o upsert bate no WITH CHECK e falha
        // fechado: conectar qualquer integração pararia de funcionar no dia do cutover. O tenant
        // vem do state assinado (HMAC, exp 10min), que é a credencial deste endpoint.
        //
        // Setar aqui basta: o `connections.upsert` é @Transactional, então o aspect dispara nele
        // e aplica o SET LOCAL na transação já aberta por este método.
        UUID tenantId = stateCodec.decode(state, clock.now()).tenantId();
        rlsContext.set(tenantId);
        try {
            return switch (provider) {
                case GOOGLE -> handleGoogleCallback(code, state);
                case SLACK -> handleSlackCallback(code, state);
                default -> handleGenericCallback(provider, code, state);
            };
        } finally {
            rlsContext.clear();
        }
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

    /** Monta a URL de autorização de um provedor genérico (code-flow padrão, state assinado). */
    private String startGeneric(IntegrationProvider provider, UUID tenantId, UUID userId) {
        OAuthProviderConfig config = requireGenericConfigured(provider);
        String state = stateCodec.encode(tenantId, userId, provider, clock.now());
        StringBuilder authorizeUrl =
                new StringBuilder(config.authorizationUrl())
                        .append("?client_id=")
                        .append(url(config.clientId()))
                        .append("&redirect_uri=")
                        .append(url(config.redirectUri()))
                        .append("&response_type=code");
        if (!config.scopes().isBlank()) {
            authorizeUrl.append("&scope=").append(url(config.scopes()));
        }
        config.extraAuthorizeParams()
                .forEach(
                        (key, value) ->
                                authorizeUrl
                                        .append('&')
                                        .append(url(key))
                                        .append('=')
                                        .append(url(value)));
        return authorizeUrl.append("&state=").append(url(state)).toString();
    }

    /**
     * Callback genérico: valida o state, troca o code pelos tokens e faz upsert da conexão. {@code
     * refreshToken} só vem de provedores com {@code supportsRefresh} (Microsoft); {@code expiresAt}
     * só é persistido quando o provedor informa {@code expires_in} (ex.: Linear ~10 anos, Microsoft
     * ~1h).
     */
    @Transactional
    public OAuthStateCodec.DecodedState handleGenericCallback(
            IntegrationProvider provider, String code, String state) {
        OAuthProviderConfig config = requireGenericConfigured(provider);
        OAuthStateCodec.DecodedState decoded = stateCodec.decode(state, clock.now());
        if (decoded.provider() != provider) {
            throw new IntegrationException.InvalidState();
        }
        GenericOAuthClient.TokenResponse tokens = genericOAuth.exchangeCode(config, code);
        OffsetDateTime now = now();
        connections.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        decoded.tenantId(),
                        decoded.userId(),
                        provider,
                        tokens.scope() == null ? config.scopes() : tokens.scope(),
                        tokens.externalAccount(),
                        tokens.accessToken(),
                        tokens.refreshToken(),
                        tokens.expiresInSeconds() == null
                                ? null
                                : now.plusSeconds(tokens.expiresInSeconds()),
                        now,
                        now));
        return decoded;
    }

    /**
     * Access token de um provedor genérico para uso imediato pelas ações do Flows. Token dentro da
     * validade (skew de 60s) volta direto. Expirado: provedores com {@code supportsRefresh}
     * (Microsoft) renovam aqui — mesma semântica do {@link #validGoogleAccessToken} (rotation
     * persistida); os demais orientam reconexão.
     */
    @Transactional
    public String validAccessToken(UUID tenantId, IntegrationProvider provider) {
        IntegrationConnection conn =
                connections
                        .findByTenantAndProvider(tenantId, provider)
                        .orElseThrow(() -> new IntegrationException.NotConnected(provider.wire()));
        OffsetDateTime now = now();
        if (conn.expiresAt() == null
                || now.plusSeconds(REFRESH_SKEW_SECONDS).isBefore(conn.expiresAt())) {
            return conn.accessToken();
        }
        OAuthProviderConfig config = providerDirectory.find(provider).orElse(null);
        if (config == null || !config.supportsRefresh()) {
            throw new IntegrationException.ProviderError(
                    provider.wire(),
                    "token expirado e o provedor não emite refresh token — reconecte a integração");
        }
        if (conn.refreshToken() == null || conn.refreshToken().isBlank()) {
            throw new IntegrationException.ProviderError(
                    provider.wire(), "token expirado e sem refresh token — reconecte a integração");
        }
        GenericOAuthClient.TokenResponse refreshed =
                genericOAuth.refresh(config, conn.refreshToken());
        IntegrationConnection updated =
                conn.withTokens(
                        refreshed.accessToken(),
                        refreshed.refreshToken(),
                        refreshed.expiresInSeconds() == null
                                ? null
                                : now.plusSeconds(refreshed.expiresInSeconds()),
                        now);
        connections.updateTokens(updated);
        return updated.accessToken();
    }

    /**
     * URL de authorize do Trello (key do app + {@code response_type=token}): o hub abre em nova
     * aba, o usuário copia o token que o Trello exibe e cola de volta ({@link #saveTrelloToken}).
     * Sem OAuth server-side (o 1.0a do Trello não vale o custo) — por isso sem state.
     */
    public String startTrello() {
        requireTrelloConfigured();
        return "https://trello.com/1/authorize?key="
                + url(trelloApiKey)
                + "&name=NORA&scope=read,write&expiration=never&response_type=token";
    }

    /**
     * Valida o token colado pelo usuário no Trello ({@code GET /1/members/me}) e persiste a conexão
     * (cifrada como as demais; token com {@code expiration=never} — sem refresh/expiração). Token
     * inválido = {@code ProviderError} claro, nada é salvo.
     */
    @Transactional
    public ProviderStatus saveTrelloToken(UUID tenantId, UUID userId, String token) {
        requireTrelloConfigured();
        if (token == null || token.isBlank()) {
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TRELLO.wire(),
                    "cole o token gerado pelo Trello antes de salvar");
        }
        String account = trello.validateToken(token.trim());
        OffsetDateTime now = now();
        connections.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        IntegrationProvider.TRELLO,
                        "read,write",
                        account,
                        token.trim(),
                        null,
                        null,
                        now,
                        now));
        return statusOf(tenantId, IntegrationProvider.TRELLO);
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
            case TELEGRAM -> telegramConfigured();
            case TRELLO -> trelloConfigured();
            default -> providerDirectory.configured(provider);
        };
    }

    /** O bot global do app está configurado? (token único — a conexão por tenant é o chat_id). */
    public boolean telegramConfigured() {
        return telegramBotToken != null && !telegramBotToken.isBlank();
    }

    private boolean trelloConfigured() {
        return trelloApiKey != null && !trelloApiKey.isBlank();
    }

    private void requireTrelloConfigured() {
        if (!trelloConfigured()) {
            throw new IntegrationException.NotConfigured(IntegrationProvider.TRELLO.wire());
        }
    }

    private OAuthProviderConfig requireGenericConfigured(IntegrationProvider provider) {
        return providerDirectory
                .find(provider)
                .orElseThrow(() -> new IntegrationException.NotConfigured(provider.wire()));
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

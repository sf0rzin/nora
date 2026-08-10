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
 * OAuth integrations hub (NORA Flows Phase 2). Flow shared by the providers: {@code start*} builds
 * the authorization URL with a signed state → the user consents at the provider → {@code
 * handle*Callback} exchanges the code for tokens, identifies the account and upserts the tenant's
 * connection.
 *
 * <p>Google: {@code validGoogleAccessToken} is what the actions (Gmail/Calendar) use at runtime —
 * renews via refresh token when expired (rotation persisted). Scopes: gmail.send, calendar.events
 * and openid email.
 *
 * <p>Slack: {@code validSlackBotToken} returns the bot token ({@code xoxb-...}) — it does NOT
 * expire, no refresh. Bot scopes: chat:write (post) and channels:read (list channels). Everything
 * tenant-scoped (ADR 0002 + RLS).
 *
 * <p>Other OAuth providers (GitHub, Notion, Todoist, Linear — wave 1; Microsoft — wave 2): standard
 * OAuth2 code-flow declared in {@link OAuthProviderDirectory} and executed by the generic client.
 * {@code validAccessToken} returns the persisted token; when the config declares {@code
 * supportsRefresh} (Microsoft), it renews with the same semantics as Google (60s skew + persisted
 * rotation); otherwise an expired token tells the user to reconnect.
 *
 * <p>Wave 2 outside OAuth: Telegram connects by code pairing (see {@code TelegramPairingService})
 * and Trello by a token the user pastes ({@link #saveTrelloToken}) — Trello's start returns
 * Trello's own authorize URL to open in a new tab.
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

    /** Margin before expiresAt to renew proactively (avoids a 401 in flight). */
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

    /** Status of each provider for the hub (connected? which account?). */
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

    /** Status of ONE provider (response of the hub's pairing/token endpoints). */
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
     * Starts the provider connection. Google/Slack have dedicated flows; Trello returns Trello's
     * own authorize URL (the user copies the generated token and pastes it into the hub); Telegram
     * does NOT go through here (code pairing — {@code TelegramPairingService}) and falls into the
     * generic path, which fails {@code NotConfigured} because it is never in the OAuth catalog; the
     * rest is generic.
     */
    public String start(IntegrationProvider provider, UUID tenantId, UUID userId) {
        return switch (provider) {
            case GOOGLE -> startGoogle(tenantId, userId);
            case SLACK -> startSlack(tenantId, userId);
            case TRELLO -> startTrello();
            default -> startGeneric(provider, tenantId, userId);
        };
    }

    /** Completes the provider's OAuth callback (same routing as {@link #start}). */
    @Transactional
    public OAuthStateCodec.DecodedState handleCallback(
            IntegrationProvider provider, String code, String state) {
        // `/integrations/*/oauth/callback` is public (SecurityConfig.PUBLIC_ENDPOINTS): it arrives
        // by redirect from the provider, without a JWT, so JwtAuthenticationFilter did not populate
        // the tenant context that TenantRlsAspect reads. V024 put RLS on integration_connections
        // with `WITH CHECK (tenant_id = nora.current_tenant_id())`, and V020 built the exemption
        // list for flows without a JWT before V024 existed — this table never made it in.
        //
        // Without the GUC, with nora.security.rls.enforce=true the upsert hits the WITH CHECK and
        // fails closed: connecting any integration would stop working on cutover day. The tenant
        // comes from the signed state (HMAC, 10min exp), which is this endpoint's credential.
        //
        // Setting it here is enough: `connections.upsert` is @Transactional, so the aspect fires
        // on it and applies the SET LOCAL on the transaction this method already opened.
        //
        // The configuration check comes BEFORE the decode so the error the operator sees does not
        // change: on a provider without credentials, decoding first returned "invalid state"
        // instead of "provider not configured", and the diagnosis pointed at the wrong side.
        switch (provider) {
            case GOOGLE -> requireGoogleConfigured();
            case SLACK -> requireSlackConfigured();
            default -> {
                /* generic: validated inside the handler, which knows the directory */
            }
        }
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

    /** Builds Google's authorization URL with a signed state (tenant/user embedded). */
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
     * Google callback: validates the state, exchanges the code for tokens and upserts the
     * connection. Returns the tenant/user from the state (the controller redirects to the front
     * end).
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
     * VALID Google access token for immediate use by the Flows actions. Renews (and persists the
     * rotation) when expired/about to expire. Throws {@code NotConnected} when there is no
     * connection.
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
                    "token expired with no refresh token — reconnect the integration");
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

    /** Builds Slack's authorization URL (OAuth v2) with a signed state. */
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
     * Slack callback: validates the state, exchanges the code for the bot token and upserts the
     * connection. The bot token does not expire — refreshToken/expiresAt stay null by contract; the
     * connected workspace (team name) becomes the external account shown in the hub.
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
     * Slack bot token for immediate use by the Flows actions. No refresh (the token does not
     * expire): returns the persisted token or throws {@code NotConnected}.
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

    /** Builds a generic provider's authorization URL (standard code-flow, signed state). */
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
     * Generic callback: validates the state, exchanges the code for the tokens and upserts the
     * connection. {@code refreshToken} only comes from providers with {@code supportsRefresh}
     * (Microsoft); {@code expiresAt} is only persisted when the provider reports {@code expires_in}
     * (e.g. Linear ~10 years, Microsoft ~1h).
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
     * Access token of a generic provider for immediate use by the Flows actions. A token still
     * within validity (60s skew) is returned directly. Expired: providers with {@code
     * supportsRefresh} (Microsoft) renew here — same semantics as {@link #validGoogleAccessToken}
     * (rotation persisted); the rest tell the user to reconnect.
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
                    "token expired and the provider does not issue a refresh token — reconnect the"
                            + " integration");
        }
        if (conn.refreshToken() == null || conn.refreshToken().isBlank()) {
            throw new IntegrationException.ProviderError(
                    provider.wire(), "token expired with no refresh token — reconnect the integration");
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
     * Trello authorize URL (app key + {@code response_type=token}): the hub opens it in a new tab,
     * the user copies the token Trello shows and pastes it back ({@link #saveTrelloToken}). No
     * server-side OAuth (Trello's 1.0a is not worth the cost) — hence no state.
     */
    public String startTrello() {
        requireTrelloConfigured();
        return "https://trello.com/1/authorize?key="
                + url(trelloApiKey)
                + "&name=NORA&scope=read,write&expiration=never&response_type=token";
    }

    /**
     * Validates the token the user pasted from Trello ({@code GET /1/members/me}) and persists the
     * connection (encrypted like the others; token with {@code expiration=never} — no
     * refresh/expiry). An invalid token = a clear {@code ProviderError}, nothing is saved.
     */
    @Transactional
    public ProviderStatus saveTrelloToken(UUID tenantId, UUID userId, String token) {
        requireTrelloConfigured();
        if (token == null || token.isBlank()) {
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TRELLO.wire(),
                    "paste the token generated by Trello before saving");
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

    /** Is the app's global bot configured? (single token — per-tenant connection is chat_id). */
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
            // An account with no visible e-mail does not block the connection — the hub shows
            // "Connected".
            LOG.warn("Could not resolve the Google account e-mail: {}", ex.getMessage());
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

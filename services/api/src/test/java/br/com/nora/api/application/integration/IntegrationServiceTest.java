package br.com.nora.api.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.GenericOAuthClient;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.SlackOAuthClient;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.ports.TrelloApi;
import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IntegrationServiceTest {

    private final Instant now = Instant.parse("2026-06-11T12:00:00Z");
    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final FakeRepo repo = new FakeRepo();
    private final FakeGoogle google = new FakeGoogle();
    private final FakeSlack slack = new FakeSlack();
    private final FakeGeneric generic = new FakeGeneric();
    private final FakeTrello trello = new FakeTrello();
    private final OAuthStateCodec codec = new OAuthStateCodec("test-secret");
    private final Clock clock = () -> now;

    /**
     * No Postgres and no aspect in this unit test: the contract exercised here is the application
     * one. The real GUC propagation is covered by IntegrationFlowIntegrationTest.
     */
    private final TenantRlsContext rlsContext =
            new TenantRlsContext() {
                @Override
                public void set(UUID tenantId) {}

                @Override
                public void clear() {}
            };

    private IntegrationService service() {
        return service(directory());
    }

    private IntegrationService service(OAuthProviderDirectory directory) {
        return new IntegrationService(
                repo,
                google,
                slack,
                generic,
                directory,
                trello,
                codec,
                clock,
                rlsContext,
                "client-id-teste",
                "http://localhost:8080/integrations/google/oauth/callback",
                "slack-client-id-teste",
                "http://localhost:8080/integrations/slack/oauth/callback",
                "telegram-bot-token-teste",
                "trello-api-key-teste");
    }

    /** Directory with the generic providers (wave 1 + Microsoft) configured. */
    private static OAuthProviderDirectory directory() {
        return new OAuthProviderDirectory(
                "github-id",
                "github-secret",
                "http://localhost:8080/integrations/github/oauth/callback",
                "notion-id",
                "notion-secret",
                "http://localhost:8080/integrations/notion/oauth/callback",
                "todoist-id",
                "todoist-secret",
                "http://localhost:8080/integrations/todoist/oauth/callback",
                "linear-id",
                "linear-secret",
                "http://localhost:8080/integrations/linear/oauth/callback",
                "microsoft-id",
                "microsoft-secret",
                "http://localhost:8080/integrations/microsoft/oauth/callback");
    }

    /** Empty directory (no generic provider configured in the environment). */
    private static OAuthProviderDirectory emptyDirectory() {
        return new OAuthProviderDirectory(
                "", "", "", "", "", "", "", "", "", "", "", "", "", "", "");
    }

    @Test
    void start_buildsUrlWithSignedState() {
        String url = service().startGoogle(tenantId, userId);
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("client_id=client-id-teste");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("gmail.send");
        assertThat(url).contains("state=");
    }

    @Test
    void start_withoutConfigurationFailsVisibly() {
        IntegrationService withoutConfig =
                new IntegrationService(
                        repo,
                        google,
                        slack,
                        generic,
                        emptyDirectory(),
                        trello,
                        codec,
                        clock,
                        rlsContext,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
        assertThatThrownBy(() -> withoutConfig.startGoogle(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
        assertThatThrownBy(() -> withoutConfig.startSlack(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
        assertThatThrownBy(() -> withoutConfig.start(IntegrationProvider.GITHUB, tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
    }

    @Test
    void callback_exchangesCodeForTokensAndUpserts() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.GOOGLE, now);
        google.exchangeResult =
                new GoogleOAuthClient.TokenResponse("at-1", "rt-1", 3600, "scope-x");
        google.email = "conta@gmail.com";

        service().handleGoogleCallback("code-abc", state);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.GOOGLE).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("at-1");
        assertThat(saved.refreshToken()).isEqualTo("rt-1");
        assertThat(saved.externalAccount()).isEqualTo("conta@gmail.com");
        assertThat(saved.expiresAt()).isEqualTo(now.atOffset(ZoneOffset.UTC).plusSeconds(3600));
    }

    @Test
    void validToken_notExpired_returnsWithoutRefresh() {
        seedConnection("at-atual", "rt-1", now.atOffset(ZoneOffset.UTC).plusSeconds(3000));
        assertThat(service().validGoogleAccessToken(tenantId)).isEqualTo("at-atual");
        assertThat(google.refreshCalls).isZero();
    }

    @Test
    void validToken_expired_renewsAndPersistsRotation() {
        seedConnection("at-velho", "rt-1", now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        google.refreshResult = new GoogleOAuthClient.TokenResponse("at-novo", null, 3600, null);

        assertThat(service().validGoogleAccessToken(tenantId)).isEqualTo("at-novo");
        assertThat(google.refreshCalls).isEqualTo(1);
        IntegrationConnection updated =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.GOOGLE).orElseThrow();
        assertThat(updated.accessToken()).isEqualTo("at-novo");
        // Google does not rotate the refresh token by default — keeps the current one.
        assertThat(updated.refreshToken()).isEqualTo("rt-1");
    }

    @Test
    void validToken_noConnection_failsClearly() {
        assertThatThrownBy(() -> service().validGoogleAccessToken(tenantId))
                .isInstanceOf(IntegrationException.NotConnected.class);
    }

    @Test
    void validToken_expiredWithNoRefreshToken_asksToReconnect() {
        seedConnection("at-velho", null, now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        assertThatThrownBy(() -> service().validGoogleAccessToken(tenantId))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("reconnect");
    }

    /* =========================== Slack =========================== */

    @Test
    void startSlack_buildsOAuthV2UrlWithSignedState() {
        String url = service().startSlack(tenantId, userId);
        assertThat(url).startsWith("https://slack.com/oauth/v2/authorize?");
        assertThat(url).contains("client_id=slack-client-id-teste");
        assertThat(url).contains("scope=chat%3Awrite%2Cchannels%3Aread");
        assertThat(url).contains("state=");
    }

    @Test
    void slackCallback_persistsBotTokenWithNoExpiryAndTeamName() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.SLACK, now);
        slack.exchangeResult =
                new SlackOAuthClient.TokenResponse(
                        "xoxb-token-1", "Time NORA", "chat:write,channels:read");

        service().handleSlackCallback("code-slack", state);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.SLACK).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("xoxb-token-1");
        // Bot token does not expire: no refresh token and no expiresAt.
        assertThat(saved.refreshToken()).isNull();
        assertThat(saved.expiresAt()).isNull();
        assertThat(saved.externalAccount()).isEqualTo("Time NORA");
    }

    @Test
    void slackCallback_stateFromAnotherProvider_fails() {
        String stateGoogle = codec.encode(tenantId, userId, IntegrationProvider.GOOGLE, now);
        assertThatThrownBy(() -> service().handleSlackCallback("code", stateGoogle))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void validSlackBotToken_returnsTokenWithoutRefresh() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.SLACK, now);
        slack.exchangeResult =
                new SlackOAuthClient.TokenResponse("xoxb-token-2", "Time NORA", null);
        service().handleSlackCallback("code", state);

        assertThat(service().validSlackBotToken(tenantId)).isEqualTo("xoxb-token-2");
    }

    @Test
    void validSlackBotToken_noConnection_failsClearly() {
        assertThatThrownBy(() -> service().validSlackBotToken(tenantId))
                .isInstanceOf(IntegrationException.NotConnected.class)
                .hasMessageContaining("slack");
    }

    @Test
    void status_listsSlackWithOwnConfigured() {
        List<ProviderStatus> status = service().status(tenantId);
        ProviderStatus slackStatus =
                status.stream().filter(s -> s.provider().equals("slack")).findFirst().orElseThrow();
        assertThat(slackStatus.configured()).isTrue();
        assertThat(slackStatus.connected()).isFalse();

        IntegrationService withoutSlack =
                new IntegrationService(
                        repo,
                        google,
                        slack,
                        generic,
                        emptyDirectory(),
                        trello,
                        codec,
                        clock,
                        rlsContext,
                        "client-id-teste",
                        "http://localhost:8080/integrations/google/oauth/callback",
                        "",
                        "",
                        "",
                        "");
        ProviderStatus notConfigured =
                withoutSlack.status(tenantId).stream()
                        .filter(s -> s.provider().equals("slack"))
                        .findFirst()
                        .orElseThrow();
        assertThat(notConfigured.configured()).isFalse();
    }

    /* ==================== Generic providers (wave 1) ==================== */

    @Test
    void startGeneric_buildsUrlWithExtraScopeAndState() {
        String github = service().start(IntegrationProvider.GITHUB, tenantId, userId);
        assertThat(github).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(github).contains("client_id=github-id");
        assertThat(github).contains("response_type=code");
        assertThat(github).contains("scope=repo");
        assertThat(github).contains("state=");

        // Notion: no scope (app capabilities) and with owner=user.
        String notion = service().start(IntegrationProvider.NOTION, tenantId, userId);
        assertThat(notion).startsWith("https://api.notion.com/v1/oauth/authorize?");
        assertThat(notion).doesNotContain("scope=");
        assertThat(notion).contains("owner=user");

        // Linear: actor=user.
        String linear = service().start(IntegrationProvider.LINEAR, tenantId, userId);
        assertThat(linear).contains("actor=user");
    }

    @Test
    void callbackGeneric_persistsTokenWithoutRefreshAndExternalAccount() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.NOTION, now);
        generic.exchangeResult =
                new GenericOAuthClient.TokenResponse(
                        "ntn-token-1", null, null, "Workspace NORA", null);

        service().handleCallback(IntegrationProvider.NOTION, "code-notion", state);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.NOTION).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("ntn-token-1");
        assertThat(saved.refreshToken()).isNull();
        assertThat(saved.expiresAt()).isNull();
        assertThat(saved.externalAccount()).isEqualTo("Workspace NORA");
    }

    @Test
    void callbackGeneric_persistsExpiresAtWhenProviderReportsIt() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.LINEAR, now);
        generic.exchangeResult =
                new GenericOAuthClient.TokenResponse("lin_token", null, "write", null, 315360000L);

        service().handleCallback(IntegrationProvider.LINEAR, "code-linear", state);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.LINEAR).orElseThrow();
        assertThat(saved.expiresAt())
                .isEqualTo(now.atOffset(ZoneOffset.UTC).plusSeconds(315360000L));
        assertThat(saved.scopes()).isEqualTo("write");
    }

    @Test
    void callbackGeneric_stateFromAnotherProvider_fails() {
        String stateGithub = codec.encode(tenantId, userId, IntegrationProvider.GITHUB, now);
        assertThatThrownBy(
                        () ->
                                service()
                                        .handleCallback(
                                                IntegrationProvider.TODOIST, "code", stateGithub))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void validAccessToken_returnsPersistedToken() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.GITHUB, now);
        generic.exchangeResult =
                new GenericOAuthClient.TokenResponse("gho_token", null, "repo", null, null);
        service().handleCallback(IntegrationProvider.GITHUB, "code", state);

        assertThat(service().validAccessToken(tenantId, IntegrationProvider.GITHUB))
                .isEqualTo("gho_token");
    }

    @Test
    void validAccessToken_noConnection_failsClearly() {
        assertThatThrownBy(() -> service().validAccessToken(tenantId, IntegrationProvider.TODOIST))
                .isInstanceOf(IntegrationException.NotConnected.class)
                .hasMessageContaining("todoist");
    }

    @Test
    void validAccessToken_expiredWithoutRefresh_asksToReconnect() {
        OffsetDateTime created = now.atOffset(ZoneOffset.UTC).minusDays(1);
        repo.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        IntegrationProvider.LINEAR,
                        "write",
                        null,
                        "lin_velho",
                        null,
                        now.atOffset(ZoneOffset.UTC).minusSeconds(10),
                        created,
                        created));
        assertThatThrownBy(() -> service().validAccessToken(tenantId, IntegrationProvider.LINEAR))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("reconnect");
    }

    @Test
    void status_listsAllNineProvidersWithOwnConfigured() {
        List<ProviderStatus> status = service().status(tenantId);
        assertThat(status)
                .extracting(ProviderStatus::provider)
                .containsExactlyInAnyOrder(
                        "google",
                        "slack",
                        "github",
                        "notion",
                        "todoist",
                        "linear",
                        "microsoft",
                        "telegram",
                        "trello");
        assertThat(status).allMatch(ProviderStatus::configured);

        List<ProviderStatus> withoutGenerics = service(emptyDirectory()).status(tenantId);
        assertThat(
                        withoutGenerics.stream()
                                .filter(s -> s.provider().equals("github"))
                                .findFirst()
                                .orElseThrow()
                                .configured())
                .isFalse();
    }

    /* ==================== Microsoft (wave 2 — generic refresh) ==================== */

    @Test
    void startMicrosoft_buildsUrlWithScopesAndState() {
        String url = service().start(IntegrationProvider.MICROSOFT, tenantId, userId);
        assertThat(url)
                .startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?");
        assertThat(url).contains("client_id=microsoft-id");
        assertThat(url).contains("offline_access");
        assertThat(url).contains("Mail.Send");
        assertThat(url).contains("state=");
    }

    @Test
    void callbackMicrosoft_persistsRefreshTokenAndExpiry() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.MICROSOFT, now);
        generic.exchangeResult =
                new GenericOAuthClient.TokenResponse(
                        "ms-at-1", "ms-rt-1", "Mail.Send", "conta@outlook.com", 3599L);

        service().handleCallback(IntegrationProvider.MICROSOFT, "code-ms", state);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.MICROSOFT).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("ms-at-1");
        assertThat(saved.refreshToken()).isEqualTo("ms-rt-1");
        assertThat(saved.externalAccount()).isEqualTo("conta@outlook.com");
        assertThat(saved.expiresAt()).isEqualTo(now.atOffset(ZoneOffset.UTC).plusSeconds(3599L));
    }

    @Test
    void validAccessTokenMicrosoft_notExpired_returnsWithoutRefresh() {
        seedMicrosoft("ms-at-atual", "ms-rt-1", now.atOffset(ZoneOffset.UTC).plusSeconds(3000));
        assertThat(service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isEqualTo("ms-at-atual");
        assertThat(generic.refreshCalls).isZero();
    }

    @Test
    void validAccessTokenMicrosoft_expired_renewsAndPersistsRotation() {
        seedMicrosoft("ms-at-velho", "ms-rt-1", now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        generic.refreshResult =
                new GenericOAuthClient.TokenResponse("ms-at-novo", "ms-rt-2", null, null, 3599L);

        assertThat(service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isEqualTo("ms-at-novo");
        assertThat(generic.refreshCalls).isEqualTo(1);
        IntegrationConnection updated =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.MICROSOFT).orElseThrow();
        assertThat(updated.accessToken()).isEqualTo("ms-at-novo");
        // Microsoft rotates the refresh token on every use — the rotation is persisted.
        assertThat(updated.refreshToken()).isEqualTo("ms-rt-2");
        assertThat(updated.expiresAt()).isEqualTo(now.atOffset(ZoneOffset.UTC).plusSeconds(3599L));
    }

    /** 60s skew: a token 30s from expiry already refreshes (same semantics as Google). */
    @Test
    void validAccessTokenMicrosoft_withinSkew_renews() {
        seedMicrosoft("ms-at-beirando", "ms-rt-1", now.atOffset(ZoneOffset.UTC).plusSeconds(30));
        generic.refreshResult =
                new GenericOAuthClient.TokenResponse("ms-at-novo", null, null, null, 3599L);

        assertThat(service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isEqualTo("ms-at-novo");
        // Refresh token not rotated by the provider — keeps the current one.
        assertThat(
                        repo.findByTenantAndProvider(tenantId, IntegrationProvider.MICROSOFT)
                                .orElseThrow()
                                .refreshToken())
                .isEqualTo("ms-rt-1");
    }

    @Test
    void validAccessTokenMicrosoft_expiredWithNoRefreshToken_asksToReconnect() {
        seedMicrosoft("ms-at-velho", null, now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        assertThatThrownBy(
                        () -> service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("reconnect");
        assertThat(generic.refreshCalls).isZero();
    }

    /* ==================== Trello (wave 2 — pasted token) ==================== */

    @Test
    void startTrello_buildsAuthorizeUrlWithAppKey() {
        String url = service().start(IntegrationProvider.TRELLO, tenantId, userId);
        assertThat(url).startsWith("https://trello.com/1/authorize?");
        assertThat(url).contains("key=trello-api-key-teste");
        assertThat(url).contains("response_type=token");
        assertThat(url).contains("expiration=never");
        // No server-side OAuth: no state/redirect — the user pastes the token back.
        assertThat(url).doesNotContain("state=");
    }

    @Test
    void saveTrelloToken_validatesWithProviderAndPersistsEncrypted() {
        trello.memberName = "Ana Martins";

        ProviderStatus status = service().saveTrelloToken(tenantId, userId, " tok-colado ");

        assertThat(trello.validatedTokens).containsExactly("tok-colado");
        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.TRELLO).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("tok-colado");
        assertThat(saved.externalAccount()).isEqualTo("Ana Martins");
        assertThat(saved.refreshToken()).isNull();
        assertThat(saved.expiresAt()).isNull();
        assertThat(status.connected()).isTrue();
        assertThat(status.externalAccount()).isEqualTo("Ana Martins");
    }

    @Test
    void saveTrelloToken_invalid_propagatesAndDoesNotPersist() {
        trello.failWith =
                new IntegrationException.ProviderError("trello", "Trello refused that token");

        assertThatThrownBy(() -> service().saveTrelloToken(tenantId, userId, "tok-ruim"))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("refused");
        assertThat(repo.findByTenantAndProvider(tenantId, IntegrationProvider.TRELLO)).isEmpty();
    }

    @Test
    void saveTrelloToken_blank_failsClearly() {
        assertThatThrownBy(() -> service().saveTrelloToken(tenantId, userId, "  "))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("paste the token");
    }

    @Test
    void saveTrelloToken_noApiKeyInEnvironment_failsNotConfigured() {
        IntegrationService withoutConfig =
                new IntegrationService(
                        repo,
                        google,
                        slack,
                        generic,
                        emptyDirectory(),
                        trello,
                        codec,
                        clock,
                        rlsContext,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "");
        assertThatThrownBy(() -> withoutConfig.saveTrelloToken(tenantId, userId, "tok"))
                .isInstanceOf(IntegrationException.NotConfigured.class);
    }

    private void seedMicrosoft(String access, String refresh, OffsetDateTime expiresAt) {
        OffsetDateTime created = now.atOffset(ZoneOffset.UTC).minusDays(1);
        repo.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        IntegrationProvider.MICROSOFT,
                        "openid email offline_access Mail.Send Calendars.ReadWrite",
                        "conta@outlook.com",
                        access,
                        refresh,
                        expiresAt,
                        created,
                        created));
    }

    private void seedConnection(String access, String refresh, OffsetDateTime expiresAt) {
        OffsetDateTime created = now.atOffset(ZoneOffset.UTC).minusDays(1);
        repo.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        tenantId,
                        userId,
                        IntegrationProvider.GOOGLE,
                        "scopes",
                        "conta@gmail.com",
                        access,
                        refresh,
                        expiresAt,
                        created,
                        created));
    }

    private static final class FakeRepo implements IntegrationConnectionRepository {
        private final Map<String, IntegrationConnection> rows = new HashMap<>();

        private static String key(UUID tenantId, IntegrationProvider provider) {
            return tenantId + "/" + provider.wire();
        }

        @Override
        public void upsert(IntegrationConnection connection) {
            rows.put(key(connection.tenantId(), connection.provider()), connection);
        }

        @Override
        public Optional<IntegrationConnection> findByTenantAndProvider(
                UUID tenantId, IntegrationProvider provider) {
            return Optional.ofNullable(rows.get(key(tenantId, provider)));
        }

        @Override
        public List<IntegrationConnection> listByTenant(UUID tenantId) {
            List<IntegrationConnection> result = new ArrayList<>();
            for (IntegrationConnection c : rows.values()) {
                if (c.tenantId().equals(tenantId)) {
                    result.add(c);
                }
            }
            return result;
        }

        @Override
        public void updateTokens(IntegrationConnection connection) {
            upsert(connection);
        }

        @Override
        public void delete(UUID tenantId, IntegrationProvider provider) {
            rows.remove(key(tenantId, provider));
        }
    }

    private static final class FakeGoogle implements GoogleOAuthClient {
        TokenResponse exchangeResult;
        TokenResponse refreshResult;
        String email;
        int refreshCalls;

        @Override
        public TokenResponse exchangeCode(String code, String redirectUri) {
            return exchangeResult;
        }

        @Override
        public TokenResponse refresh(String refreshToken) {
            refreshCalls++;
            return refreshResult;
        }

        @Override
        public String userEmail(String accessToken) {
            return email;
        }
    }

    private static final class FakeSlack implements SlackOAuthClient {
        TokenResponse exchangeResult;

        @Override
        public TokenResponse exchangeCode(String code, String redirectUri) {
            return exchangeResult;
        }
    }

    private static final class FakeGeneric implements GenericOAuthClient {
        TokenResponse exchangeResult;
        TokenResponse refreshResult;
        int refreshCalls;

        @Override
        public TokenResponse exchangeCode(OAuthProviderConfig config, String code) {
            return exchangeResult;
        }

        @Override
        public TokenResponse refresh(OAuthProviderConfig config, String refreshToken) {
            refreshCalls++;
            return refreshResult;
        }
    }

    private static final class FakeTrello implements TrelloApi {
        String memberName;
        RuntimeException failWith;
        final List<String> validatedTokens = new ArrayList<>();

        @Override
        public String validateToken(String token) {
            if (failWith != null) {
                throw failWith;
            }
            validatedTokens.add(token);
            return memberName;
        }
    }
}

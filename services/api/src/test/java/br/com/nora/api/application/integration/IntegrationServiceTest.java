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
    private final OAuthStateCodec codec = new OAuthStateCodec("segredo-teste");
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
    void start_montaUrlComStateAssinado() {
        String url = service().startGoogle(tenantId, userId);
        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("client_id=client-id-teste");
        assertThat(url).contains("access_type=offline");
        assertThat(url).contains("prompt=consent");
        assertThat(url).contains("gmail.send");
        assertThat(url).contains("state=");
    }

    @Test
    void start_semConfiguracaoFalhaVisivel() {
        IntegrationService semConfig =
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
        assertThatThrownBy(() -> semConfig.startGoogle(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
        assertThatThrownBy(() -> semConfig.startSlack(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
        assertThatThrownBy(() -> semConfig.start(IntegrationProvider.GITHUB, tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
    }

    @Test
    void callback_trocaCodePorTokensEFazUpsert() {
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
    void validToken_naoExpirado_devolveSemRefresh() {
        seedConnection("at-atual", "rt-1", now.atOffset(ZoneOffset.UTC).plusSeconds(3000));
        assertThat(service().validGoogleAccessToken(tenantId)).isEqualTo("at-atual");
        assertThat(google.refreshCalls).isZero();
    }

    @Test
    void validToken_expirado_renovaEPersisteRotation() {
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
    void validToken_semConexao_falhaClaro() {
        assertThatThrownBy(() -> service().validGoogleAccessToken(tenantId))
                .isInstanceOf(IntegrationException.NotConnected.class);
    }

    @Test
    void validToken_expiradoSemRefreshToken_pedeReconexao() {
        seedConnection("at-velho", null, now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        assertThatThrownBy(() -> service().validGoogleAccessToken(tenantId))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("reconecte");
    }

    /* =========================== Slack =========================== */

    @Test
    void startSlack_montaUrlOAuthV2ComStateAssinado() {
        String url = service().startSlack(tenantId, userId);
        assertThat(url).startsWith("https://slack.com/oauth/v2/authorize?");
        assertThat(url).contains("client_id=slack-client-id-teste");
        assertThat(url).contains("scope=chat%3Awrite%2Cchannels%3Aread");
        assertThat(url).contains("state=");
    }

    @Test
    void slackCallback_persisteBotTokenSemExpiracaoComTeamName() {
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
    void slackCallback_stateDeOutroProvedor_falha() {
        String stateGoogle = codec.encode(tenantId, userId, IntegrationProvider.GOOGLE, now);
        assertThatThrownBy(() -> service().handleSlackCallback("code", stateGoogle))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void validSlackBotToken_devolveTokenSemRefresh() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.SLACK, now);
        slack.exchangeResult =
                new SlackOAuthClient.TokenResponse("xoxb-token-2", "Time NORA", null);
        service().handleSlackCallback("code", state);

        assertThat(service().validSlackBotToken(tenantId)).isEqualTo("xoxb-token-2");
    }

    @Test
    void validSlackBotToken_semConexao_falhaClaro() {
        assertThatThrownBy(() -> service().validSlackBotToken(tenantId))
                .isInstanceOf(IntegrationException.NotConnected.class)
                .hasMessageContaining("slack");
    }

    @Test
    void status_listaSlackComConfiguredProprio() {
        List<ProviderStatus> status = service().status(tenantId);
        ProviderStatus slackStatus =
                status.stream().filter(s -> s.provider().equals("slack")).findFirst().orElseThrow();
        assertThat(slackStatus.configured()).isTrue();
        assertThat(slackStatus.connected()).isFalse();

        IntegrationService semSlack =
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
        ProviderStatus naoConfigurado =
                semSlack.status(tenantId).stream()
                        .filter(s -> s.provider().equals("slack"))
                        .findFirst()
                        .orElseThrow();
        assertThat(naoConfigurado.configured()).isFalse();
    }

    /* ==================== Generic providers (wave 1) ==================== */

    @Test
    void startGenerico_montaUrlComScopeExtrasEState() {
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
    void callbackGenerico_persisteTokenSemRefreshComContaExterna() {
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
    void callbackGenerico_persisteExpiresAtQuandoProvedorInforma() {
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
    void callbackGenerico_stateDeOutroProvedor_falha() {
        String stateGithub = codec.encode(tenantId, userId, IntegrationProvider.GITHUB, now);
        assertThatThrownBy(
                        () ->
                                service()
                                        .handleCallback(
                                                IntegrationProvider.TODOIST, "code", stateGithub))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void validAccessToken_devolveTokenPersistido() {
        String state = codec.encode(tenantId, userId, IntegrationProvider.GITHUB, now);
        generic.exchangeResult =
                new GenericOAuthClient.TokenResponse("gho_token", null, "repo", null, null);
        service().handleCallback(IntegrationProvider.GITHUB, "code", state);

        assertThat(service().validAccessToken(tenantId, IntegrationProvider.GITHUB))
                .isEqualTo("gho_token");
    }

    @Test
    void validAccessToken_semConexao_falhaClaro() {
        assertThatThrownBy(() -> service().validAccessToken(tenantId, IntegrationProvider.TODOIST))
                .isInstanceOf(IntegrationException.NotConnected.class)
                .hasMessageContaining("todoist");
    }

    @Test
    void validAccessToken_expiradoSemRefresh_pedeReconexao() {
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
                .hasMessageContaining("reconecte");
    }

    @Test
    void status_listaOsNoveProvedoresComConfiguredProprio() {
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

        List<ProviderStatus> semGenericos = service(emptyDirectory()).status(tenantId);
        assertThat(
                        semGenericos.stream()
                                .filter(s -> s.provider().equals("github"))
                                .findFirst()
                                .orElseThrow()
                                .configured())
                .isFalse();
    }

    /* ==================== Microsoft (wave 2 — generic refresh) ==================== */

    @Test
    void startMicrosoft_montaUrlComScopesEState() {
        String url = service().start(IntegrationProvider.MICROSOFT, tenantId, userId);
        assertThat(url)
                .startsWith("https://login.microsoftonline.com/common/oauth2/v2.0/authorize?");
        assertThat(url).contains("client_id=microsoft-id");
        assertThat(url).contains("offline_access");
        assertThat(url).contains("Mail.Send");
        assertThat(url).contains("state=");
    }

    @Test
    void callbackMicrosoft_persisteRefreshTokenEExpiracao() {
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
    void validAccessTokenMicrosoft_naoExpirado_devolveSemRefresh() {
        seedMicrosoft("ms-at-atual", "ms-rt-1", now.atOffset(ZoneOffset.UTC).plusSeconds(3000));
        assertThat(service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isEqualTo("ms-at-atual");
        assertThat(generic.refreshCalls).isZero();
    }

    @Test
    void validAccessTokenMicrosoft_expirado_renovaEPersisteRotation() {
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
    void validAccessTokenMicrosoft_dentroDoSkew_renova() {
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
    void validAccessTokenMicrosoft_expiradoSemRefreshToken_pedeReconexao() {
        seedMicrosoft("ms-at-velho", null, now.atOffset(ZoneOffset.UTC).minusSeconds(10));
        assertThatThrownBy(
                        () -> service().validAccessToken(tenantId, IntegrationProvider.MICROSOFT))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("reconecte");
        assertThat(generic.refreshCalls).isZero();
    }

    /* ==================== Trello (wave 2 — pasted token) ==================== */

    @Test
    void startTrello_montaUrlDeAuthorizeComKeyDoApp() {
        String url = service().start(IntegrationProvider.TRELLO, tenantId, userId);
        assertThat(url).startsWith("https://trello.com/1/authorize?");
        assertThat(url).contains("key=trello-api-key-teste");
        assertThat(url).contains("response_type=token");
        assertThat(url).contains("expiration=never");
        // No server-side OAuth: no state/redirect — the user pastes the token back.
        assertThat(url).doesNotContain("state=");
    }

    @Test
    void saveTrelloToken_validaNoProvedorEPersisteCifrado() {
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
    void saveTrelloToken_invalido_propagaENaoPersiste() {
        trello.failWith =
                new IntegrationException.ProviderError("trello", "o Trello recusou esse token");

        assertThatThrownBy(() -> service().saveTrelloToken(tenantId, userId, "tok-ruim"))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("recusou");
        assertThat(repo.findByTenantAndProvider(tenantId, IntegrationProvider.TRELLO)).isEmpty();
    }

    @Test
    void saveTrelloToken_vazio_falhaClaro() {
        assertThatThrownBy(() -> service().saveTrelloToken(tenantId, userId, "  "))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("cole o token");
    }

    @Test
    void saveTrelloToken_semApiKeyNoAmbiente_falhaNotConfigured() {
        IntegrationService semConfig =
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
        assertThatThrownBy(() -> semConfig.saveTrelloToken(tenantId, userId, "tok"))
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

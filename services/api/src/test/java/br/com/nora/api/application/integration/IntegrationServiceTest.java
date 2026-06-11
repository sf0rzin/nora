package br.com.nora.api.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.SlackOAuthClient;
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
    private final OAuthStateCodec codec = new OAuthStateCodec("segredo-teste");
    private final Clock clock = () -> now;

    private IntegrationService service() {
        return new IntegrationService(
                repo,
                google,
                slack,
                codec,
                clock,
                "client-id-teste",
                "http://localhost:8080/integrations/google/oauth/callback",
                "slack-client-id-teste",
                "http://localhost:8080/integrations/slack/oauth/callback");
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
                new IntegrationService(repo, google, slack, codec, clock, "", "", "", "");
        assertThatThrownBy(() -> semConfig.startGoogle(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
        assertThatThrownBy(() -> semConfig.startSlack(tenantId, userId))
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
        // Google não rotaciona refresh token por padrão — mantém o atual.
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
        // Bot token não expira: sem refresh token e sem expiresAt.
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
                        codec,
                        clock,
                        "client-id-teste",
                        "http://localhost:8080/integrations/google/oauth/callback",
                        "",
                        "");
        ProviderStatus naoConfigurado =
                semSlack.status(tenantId).stream()
                        .filter(s -> s.provider().equals("slack"))
                        .findFirst()
                        .orElseThrow();
        assertThat(naoConfigurado.configured()).isFalse();
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
}

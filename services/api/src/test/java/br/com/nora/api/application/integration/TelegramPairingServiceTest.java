package br.com.nora.api.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.TelegramBotApi;
import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pareamento Telegram: código por tenant (TTL 10 min), deep link do bot, verify que varre o
 * getUpdates (one-shot — cada update chega UMA vez, por isso o cache de /start vistos).
 */
class TelegramPairingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    /** Relógio mutável pra simular expiração do código. */
    private final Instant[] currentTime = {Instant.parse("2026-06-12T12:00:00Z")};

    private final Clock clock = () -> currentTime[0];

    private final FakeBot bot = new FakeBot();
    private final FakeRepo repo = new FakeRepo();
    private final IntegrationService integrations = mock(IntegrationService.class);

    private final TelegramPairingService service =
            new TelegramPairingService(bot, repo, integrations, clock);

    @BeforeEach
    void setUp() {
        when(integrations.telegramConfigured()).thenReturn(true);
        when(integrations.statusOf(eq(tenantId), eq(IntegrationProvider.TELEGRAM)))
                .thenAnswer(
                        inv -> {
                            Optional<IntegrationConnection> conn =
                                    repo.findByTenantAndProvider(
                                            tenantId, IntegrationProvider.TELEGRAM);
                            return new ProviderStatus(
                                    "telegram",
                                    true,
                                    conn.isPresent(),
                                    conn.map(IntegrationConnection::externalAccount).orElse(null),
                                    null);
                        });
    }

    @Test
    void start_geraCodigoDe8CharsEDeepLinkDoBot() {
        TelegramPairingService.PairingStart pairing = service.start(tenantId, userId);

        assertThat(pairing.code()).hasSize(8).matches("[A-Z2-9]+");
        assertThat(pairing.deepLink()).isEqualTo("https://t.me/nora_bot?start=" + pairing.code());
    }

    @Test
    void start_semBotTokenNoAmbiente_falhaNotConfigured() {
        when(integrations.telegramConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.start(tenantId, userId))
                .isInstanceOf(IntegrationException.NotConfigured.class);
    }

    @Test
    void verify_semPareamentoIniciado_orientaConectar() {
        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("nenhum pareamento em andamento");
    }

    @Test
    void verify_semStartAinda_pendenteComMensagemAcionavel() {
        service.start(tenantId, userId);

        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.PairingPending.class)
                .hasMessageContaining("ainda não recebi seu /start");
        // Pareamento continua vivo — o usuário pode tentar de novo.
        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.PairingPending.class);
    }

    @Test
    void verify_achaStartESalvaChatIdComoConexao() {
        TelegramPairingService.PairingStart pairing = service.start(tenantId, userId);
        bot.incoming.add(new TelegramBotApi.StartCommand("123456789", pairing.code(), "Ana"));

        ProviderStatus status = service.verify(tenantId);

        IntegrationConnection saved =
                repo.findByTenantAndProvider(tenantId, IntegrationProvider.TELEGRAM).orElseThrow();
        assertThat(saved.accessToken()).isEqualTo("123456789");
        assertThat(saved.externalAccount()).isEqualTo("Ana");
        assertThat(saved.connectedByUserId()).isEqualTo(userId);
        assertThat(saved.refreshToken()).isNull();
        assertThat(saved.expiresAt()).isNull();
        assertThat(status.connected()).isTrue();

        // Código consumido: verify de novo exige novo pareamento.
        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("nenhum pareamento");
    }

    @Test
    void verify_codigoDeOutroUsuarioNaoCasa() {
        service.start(tenantId, userId);
        bot.incoming.add(new TelegramBotApi.StartCommand("999", "CODIGOXX", "Intruso"));

        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.PairingPending.class);
        assertThat(repo.findByTenantAndProvider(tenantId, IntegrationProvider.TELEGRAM)).isEmpty();
    }

    @Test
    void verify_codigoExpirado_orientaGerarOutro() {
        service.start(tenantId, userId);
        currentTime[0] = currentTime[0].plusSeconds(601);

        assertThatThrownBy(() -> service.verify(tenantId))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("expirou");
    }

    /**
     * getUpdates é one-shot: um /start absorvido durante o verify de OUTRO tenant fica no cache e
     * casa no verify seguinte do tenant dono do código.
     */
    @Test
    void verify_startAbsorvidoEmPollDeOutroTenant_casaDepois() {
        UUID outroTenant = UUID.randomUUID();
        when(integrations.statusOf(eq(outroTenant), eq(IntegrationProvider.TELEGRAM)))
                .thenReturn(new ProviderStatus("telegram", true, false, null, null));

        TelegramPairingService.PairingStart meuPairing = service.start(tenantId, userId);
        service.start(outroTenant, UUID.randomUUID());
        bot.incoming.add(new TelegramBotApi.StartCommand("123", meuPairing.code(), "Ana"));

        // O outro tenant verifica primeiro: consome o getUpdates mas não casa o código.
        assertThatThrownBy(() -> service.verify(outroTenant))
                .isInstanceOf(IntegrationException.PairingPending.class);
        assertThat(bot.incoming).isEmpty();

        // Meu verify acha o /start no cache mesmo com o getUpdates já vazio.
        ProviderStatus status = service.verify(tenantId);
        assertThat(status.connected()).isTrue();
    }

    /** Polls subsequentes não devolvem updates já entregues (semântica do offset). */
    private static final class FakeBot implements TelegramBotApi {
        final List<StartCommand> incoming = new ArrayList<>();

        @Override
        public String botUsername() {
            return "nora_bot";
        }

        @Override
        public List<StartCommand> pollStartCommands() {
            List<StartCommand> delivered = new ArrayList<>(incoming);
            incoming.clear();
            return delivered;
        }
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
}

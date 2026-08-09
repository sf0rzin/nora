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
 * Telegram pairing: code per tenant (TTL 10 min), bot deep link, verify that sweeps getUpdates
 * (one-shot — each update arrives ONCE, hence the cache of seen /start).
 */
class TelegramPairingServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    /** Mutable clock to simulate expiry of the code. */
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
        // Pairing stays alive — the user can try again.
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

        // Code consumed: verifying again requires a new pairing.
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
     * getUpdates is one-shot: a /start absorbed during ANOTHER tenant's verify stays in the cache
     * and matches on the next verify of the tenant that owns the code.
     */
    @Test
    void verify_startAbsorvidoEmPollDeOutroTenant_casaDepois() {
        UUID outroTenant = UUID.randomUUID();
        when(integrations.statusOf(eq(outroTenant), eq(IntegrationProvider.TELEGRAM)))
                .thenReturn(new ProviderStatus("telegram", true, false, null, null));

        TelegramPairingService.PairingStart meuPairing = service.start(tenantId, userId);
        service.start(outroTenant, UUID.randomUUID());
        bot.incoming.add(new TelegramBotApi.StartCommand("123", meuPairing.code(), "Ana"));

        // The other tenant verifies first: it consumes getUpdates but does not match the code.
        assertThatThrownBy(() -> service.verify(outroTenant))
                .isInstanceOf(IntegrationException.PairingPending.class);
        assertThat(bot.incoming).isEmpty();

        // My verify finds the /start in the cache even with getUpdates already empty.
        ProviderStatus status = service.verify(tenantId);
        assertThat(status.connected()).isTrue();
    }

    /** Subsequent polls do not return already delivered updates (offset semantics). */
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

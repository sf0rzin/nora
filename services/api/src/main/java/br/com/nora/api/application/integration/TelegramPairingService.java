package br.com.nora.api.application.integration;

import br.com.nora.api.application.integration.IntegrationService.ProviderStatus;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.IntegrationConnectionRepository;
import br.com.nora.api.application.ports.TelegramBotApi;
import br.com.nora.api.domain.integration.IntegrationConnection;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pareamento do Telegram (onda 2) — o Telegram NÃO tem OAuth, então a conexão prova posse de um
 * código curto: {@code start} gera o código (8 chars, TTL 10 min, um pendente por tenant) e devolve
 * o deep link {@code t.me/<bot>?start=<código>}; o usuário abre o link e manda o /start; {@code
 * verify} varre o getUpdates do bot atrás de {@code /start <código>} e, achando, persiste o {@code
 * chat_id} como conexão do tenant (cifrado como os demais tokens; sem refresh/expiração).
 *
 * <p>Estado em memória por design (códigos efêmeros, mesmo espírito do state HMAC): códigos
 * pendentes por tenant + /start vistos no poll (sobrevivem entre verifies — o getUpdates avança o
 * offset, então cada update só chega uma vez). Restart do app no meio do pareamento = gerar código
 * novo, fail-visible e sem migration.
 */
@Service
public class TelegramPairingService {

    /** Mesmo TTL do state OAuth (10 min). */
    private static final long CODE_TTL_SECONDS = 600;

    /** Sem 0/O/1/I/L — o usuário pode acabar digitando o código à mão. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int CODE_LENGTH = 8;

    private final TelegramBotApi bot;
    private final IntegrationConnectionRepository connections;
    private final IntegrationService integrations;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Código pendente por tenant (um pareamento em andamento por vez). */
    private final Map<UUID, PendingCode> pending = new ConcurrentHashMap<>();

    /** /start vistos no getUpdates e ainda não casados (código → chat), com TTL próprio. */
    private final Map<String, SeenStart> seenStarts = new ConcurrentHashMap<>();

    public TelegramPairingService(
            TelegramBotApi bot,
            IntegrationConnectionRepository connections,
            IntegrationService integrations,
            Clock clock) {
        this.bot = bot;
        this.connections = connections;
        this.integrations = integrations;
        this.clock = clock;
    }

    /** Gera o código do tenant e devolve o deep link do bot (getMe cacheado pelo client). */
    public PairingStart start(UUID tenantId, UUID userId) {
        requireConfigured();
        String code = generateCode();
        pending.put(
                tenantId, new PendingCode(code, userId, clock.now().plusSeconds(CODE_TTL_SECONDS)));
        String deepLink = "https://t.me/" + bot.botUsername() + "?start=" + code;
        return new PairingStart(deepLink, code);
    }

    /**
     * Procura o {@code /start <código>} do tenant no getUpdates e conclui a conexão. Sem o /start
     * ainda = {@code PairingPending} (o hub orienta tentar de novo); código expirado ou pareamento
     * não iniciado = {@code ProviderError} com instrução de recomeçar.
     */
    @Transactional
    public ProviderStatus verify(UUID tenantId) {
        requireConfigured();
        Instant now = clock.now();
        PendingCode code = pending.get(tenantId);
        if (code == null) {
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TELEGRAM.wire(),
                    "nenhum pareamento em andamento — clique em Conectar para gerar o código");
        }
        if (now.isAfter(code.expiresAt())) {
            pending.remove(tenantId);
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TELEGRAM.wire(),
                    "o código de pareamento expirou — clique em Conectar para gerar outro");
        }

        absorbUpdates(now);

        SeenStart match = seenStarts.remove(code.code());
        if (match == null) {
            throw new IntegrationException.PairingPending(
                    "ainda não recebi seu /start — abra o link e tente de novo");
        }

        OffsetDateTime nowOffset = now.atOffset(ZoneOffset.UTC);
        connections.upsert(
                new IntegrationConnection(
                        UUID.randomUUID(),
                        tenantId,
                        code.userId(),
                        IntegrationProvider.TELEGRAM,
                        "bot",
                        match.fromDisplay() == null ? "Telegram" : match.fromDisplay(),
                        match.chatId(),
                        null,
                        null,
                        nowOffset,
                        nowOffset));
        pending.remove(tenantId);
        return integrations.statusOf(tenantId, IntegrationProvider.TELEGRAM);
    }

    /**
     * Puxa o getUpdates (one-shot; offset avança no client) e guarda os /start vistos — cada update
     * só chega UMA vez, então o cache garante que um /start observado num verify de outro tenant
     * não se perde. Entradas velhas (> TTL) são descartadas.
     */
    private void absorbUpdates(Instant now) {
        for (TelegramBotApi.StartCommand cmd : bot.pollStartCommands()) {
            if (cmd.payload() != null && !cmd.payload().isBlank()) {
                seenStarts.put(
                        cmd.payload().trim(), new SeenStart(cmd.chatId(), cmd.fromDisplay(), now));
            }
        }
        seenStarts
                .entrySet()
                .removeIf(e -> now.isAfter(e.getValue().seenAt().plusSeconds(CODE_TTL_SECONDS)));
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private void requireConfigured() {
        if (!integrations.telegramConfigured()) {
            throw new IntegrationException.NotConfigured(IntegrationProvider.TELEGRAM.wire());
        }
    }

    /** Resposta do start: deep link pronto pro botão "Abrir no Telegram" + código exibido. */
    public record PairingStart(String deepLink, String code) {}

    private record PendingCode(String code, UUID userId, Instant expiresAt) {}

    private record SeenStart(String chatId, String fromDisplay, Instant seenAt) {}
}

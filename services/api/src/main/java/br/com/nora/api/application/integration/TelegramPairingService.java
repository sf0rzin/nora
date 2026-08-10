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
 * Telegram pairing (wave 2) — Telegram has NO OAuth, so the connection proves possession of a short
 * code: {@code start} generates the code (8 chars, TTL 10 min, one pending per tenant) and returns
 * the deep link {@code t.me/<bot>?start=<code>}; the user opens the link and sends /start; {@code
 * verify} sweeps the bot's getUpdates looking for {@code /start <code>} and, on a hit, persists the
 * {@code chat_id} as the tenant's connection (encrypted like the other tokens; no refresh/expiry).
 *
 * <p>In-memory state by design (ephemeral codes, same spirit as the HMAC state): pending codes per
 * tenant + /start commands seen in the poll (they survive between verifies — getUpdates advances
 * the offset, so each update only arrives once). Restarting the app mid-pairing = generate a new
 * code, fail-visible and with no migration.
 */
@Service
public class TelegramPairingService {

    /** Same TTL as the OAuth state (10 min). */
    private static final long CODE_TTL_SECONDS = 600;

    /** No 0/O/1/I/L — the user may end up typing the code by hand. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private static final int CODE_LENGTH = 8;

    private final TelegramBotApi bot;
    private final IntegrationConnectionRepository connections;
    private final IntegrationService integrations;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** Pending code per tenant (one pairing in progress at a time). */
    private final Map<UUID, PendingCode> pending = new ConcurrentHashMap<>();

    /** /start seen in getUpdates and not yet matched (code → chat), with their own TTL. */
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

    /** Generates the tenant's code and returns the bot deep link (getMe cached by the client). */
    public PairingStart start(UUID tenantId, UUID userId) {
        requireConfigured();
        String code = generateCode();
        pending.put(
                tenantId, new PendingCode(code, userId, clock.now().plusSeconds(CODE_TTL_SECONDS)));
        String deepLink = "https://t.me/" + bot.botUsername() + "?start=" + code;
        return new PairingStart(deepLink, code);
    }

    /**
     * Looks for the tenant's {@code /start <code>} in getUpdates and completes the connection. No
     * /start yet = {@code PairingPending} (the hub tells the user to try again); expired code or
     * pairing not started = {@code ProviderError} with an instruction to start over.
     */
    @Transactional
    public ProviderStatus verify(UUID tenantId) {
        requireConfigured();
        Instant now = clock.now();
        PendingCode code = pending.get(tenantId);
        if (code == null) {
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TELEGRAM.wire(),
                    "no pairing in progress — click Connect to generate the code");
        }
        if (now.isAfter(code.expiresAt())) {
            pending.remove(tenantId);
            throw new IntegrationException.ProviderError(
                    IntegrationProvider.TELEGRAM.wire(),
                    "the pairing code expired — click Connect to generate another one");
        }

        absorbUpdates(now);

        SeenStart match = seenStarts.remove(code.code());
        if (match == null) {
            throw new IntegrationException.PairingPending(
                    "we still haven't received your /start — open the link and try again");
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
     * Pulls getUpdates (one-shot; the offset advances in the client) and stores the /start seen —
     * each update only arrives ONCE, so the cache guarantees that a /start observed during another
     * tenant's verify is not lost. Stale entries (> TTL) are discarded.
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

    /** Start response: deep link ready for the "Open in Telegram" button + the code shown. */
    public record PairingStart(String deepLink, String code) {}

    private record PendingCode(String code, UUID userId, Instant expiresAt) {}

    private record SeenStart(String chatId, String fromDisplay, Instant seenAt) {}
}

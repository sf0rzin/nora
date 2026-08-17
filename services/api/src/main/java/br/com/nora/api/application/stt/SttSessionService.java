package br.com.nora.api.application.stt;

import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.stt.ports.RealtimeSttBroker;
import br.com.nora.api.infrastructure.stt.SttProperties;
import br.com.nora.api.infrastructure.stt.SttRateLimiter;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Mints one realtime transcription session for the caller (ADR 0039, ADR 0045).
 *
 * <p>Order matters and is the same order the deleted {@code SpeechTokenService} used: consume the
 * rate limiter FIRST, then resolve the language, then call the broker, then record the issuance.
 * The limiter guards a paid provider, so it has to run before the call that costs money.
 */
@Service
public class SttSessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SttSessionService.class);

    private final RealtimeSttBroker broker;
    private final SttRateLimiter rateLimiter;
    private final SttProperties props;
    private final UsageRecorder usage;

    public SttSessionService(
            RealtimeSttBroker broker,
            SttRateLimiter rateLimiter,
            SttProperties props,
            UsageRecorder usage) {
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.usage = usage;
    }

    public RealtimeSttSession openSession(UUID userId, UUID tenantId, String requestedLanguage) {
        if (!rateLimiter.tryConsume(userId)) {
            throw new SttException.RateLimited();
        }

        String language = normaliseLanguage(requestedLanguage);
        long startedAt = System.nanoTime();
        try {
            RealtimeSttSession session = broker.openSession(language);
            recordIssuance(tenantId, elapsedMs(startedAt), "ok");
            LOG.info(
                    "STT session issued: tenant={} provider={} model={} language={} expiresAt={}",
                    tenantId,
                    session.provider(),
                    session.model(),
                    session.language(),
                    session.expiresAt());
            return session;
        } catch (SttException e) {
            recordIssuance(tenantId, elapsedMs(startedAt), "error");
            throw e;
        }
    }

    /**
     * Attribution, and the honest limit of it.
     *
     * <p>What is counted here is a SESSION ISSUED, not a minute transcribed. With the ephemeral
     * credential of ADR 0039 the audio goes desktop → provider and never crosses NORA's
     * infrastructure, so this API can say which tenant asked for how many sessions and when, and
     * cannot say how much audio any of them carried. Token counts are zero because a transcription
     * session has none, and the cost hint is null because inventing one from a client-reported
     * duration would put a fabricated number into the operator console (ADR 0039 §"The audio does
     * not pass through NORA's infrastructure"; ADR 0024 is the surface that displays it, and it
     * must label these rows as estimates).
     *
     * <p>Recorded on failure too, with {@code status=error}: a tenant whose sessions are all being
     * refused is exactly the thing the console should be able to see.
     */
    private void recordIssuance(UUID tenantId, Integer latencyMs, String status) {
        usage.recordExternal(
                "stt", broker.provider(), broker.model(), tenantId, 0, 0, null, latencyMs, status);
    }

    /**
     * The desktop speaks BCP-47 ({@code pt-BR}); the provider's transcription config takes an
     * ISO-639-1 primary subtag. Sending the full tag through has been accepted silently by
     * providers before and then ignored, which reads as "language hint did nothing" rather than as
     * an error, so the narrowing happens here and once.
     *
     * <p>An unusable value degrades to the configured default instead of failing the request: a
     * recording that starts with the wrong language hint is recoverable, one that does not start is
     * not.
     */
    private String normaliseLanguage(String requested) {
        String candidate =
                (requested == null || requested.isBlank()) ? props.defaultLanguage() : requested;
        String primary = candidate.trim().toLowerCase(Locale.ROOT).split("[-_]", 2)[0];
        boolean usable =
                primary.length() >= 2
                        && primary.length() <= 3
                        && primary.chars().allMatch(Character::isLetter);
        if (!usable) {
            LOG.warn("STT session: unusable language {} — falling back to the default", requested);
            return props.defaultLanguage();
        }
        return primary;
    }

    private static Integer elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}

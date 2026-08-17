package br.com.nora.api.infrastructure.stt;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-user budget of minted realtime STT sessions, in front of a paid provider.
 *
 * <p>Same shape as the deleted {@code SpeechRateLimiter}, and it is consumed in the same place: in
 * the application service, BEFORE the broker is called. Consuming after the broker call would make
 * the limiter protect nothing — the money is spent when the provider creates the session.
 *
 * <p>In-memory and per-instance, like every other limiter in this API. That is honest for a
 * single-instance deployment (ADR 0036) and would need a shared store the day there are two.
 */
@Component
public class SttRateLimiter {

    private final SttProperties props;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public SttRateLimiter(SttProperties props) {
        this.props = props;
    }

    public boolean tryConsume(UUID userId) {
        Bucket bucket =
                buckets.computeIfAbsent(
                        userId,
                        id ->
                                Bucket.builder()
                                        .addLimit(
                                                Bandwidth.simple(
                                                        props.rateLimit().sessionsPerMinute(),
                                                        Duration.ofMinutes(1)))
                                        .build());
        return bucket.tryConsume(1);
    }
}

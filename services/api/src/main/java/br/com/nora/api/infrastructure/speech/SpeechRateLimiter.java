package br.com.nora.api.infrastructure.speech;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SpeechRateLimiter {

    private final SpeechProperties props;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public SpeechRateLimiter(SpeechProperties props) {
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
                                                        props.rateLimit().tokensPerMinute(),
                                                        Duration.ofMinutes(1)))
                                        .build());
        return bucket.tryConsume(1);
    }
}

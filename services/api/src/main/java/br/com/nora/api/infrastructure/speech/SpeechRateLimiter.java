package br.com.nora.api.infrastructure.speech;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SpeechRateLimiter {

    /** Teto de buckets em memoria; evita crescimento ilimitado do mapa por usuario. */
    private static final long MAX_BUCKETS = 10_000;

    private final SpeechProperties props;

    /**
     * Buckets por usuario com eviction. O {@link ConcurrentHashMap} anterior nunca limpava: cada
     * usuario que algum dia pediu um token de fala deixava uma entrada permanente, e o mapa so
     * crescia pela vida do JVM. Caffeine com {@code maximumSize} + {@code expireAfterAccess} (3x a
     * janela) coleta buckets ociosos sem derrubar quem ainda esta dentro da janela — mesmo padrao
     * do {@code AuthRateLimiter}.
     */
    private final Cache<UUID, Bucket> buckets;

    public SpeechRateLimiter(SpeechProperties props) {
        this.props = props;
        this.buckets =
                Caffeine.newBuilder()
                        .maximumSize(MAX_BUCKETS)
                        .expireAfterAccess(Duration.ofMinutes(3))
                        .build();
    }

    public boolean tryConsume(UUID userId) {
        Bucket bucket =
                buckets.get(
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

package br.com.nora.api.infrastructure.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-user budget for the request paths that spend money with an external AI provider.
 *
 * <p><b>Why this exists.</b> The rule was already written down in {@code SttSessionService}: "the
 * limiter guards a paid provider, so it has to run before the call that costs money". It had been
 * applied to exactly one endpoint. Semantic search bills an embedding call per request, live
 * analysis calls the LLM per transcript chunk from a desktop client that runs in a loop for the
 * length of a meeting, split-preview sends a whole file through the LLM, and reprocess re-runs the
 * full analysis of a meeting — four paths where a client stuck in a retry loop turns into a
 * provider invoice, with nothing in front of them. Every other control in this system caps what a
 * caller may DO; this one caps what a caller may SPEND, and no permission expresses that: the
 * grants are legitimate and the spending is still unbounded.
 *
 * <p><b>Keyed by user, not by IP.</b> All four paths are authenticated, so the principal is known
 * and is the thing that gets billed. Two of them are also reachable with an MCP bearer credential
 * ({@code search_meetings}), which resolves to the same user id — so the same budget covers the
 * browser and the agent instead of giving an automated client its own.
 *
 * <p>In-memory and per-instance, like every other limiter in this API, and honest for a
 * single-instance deployment (ADR 0036). Caffeine bounds the number of buckets so the limiter
 * cannot itself become the memory problem, with an eviction window comfortably above the refill
 * window so a caller inside their minute never gets a fresh bucket.
 *
 * <p>The defaults are set where a human cannot reach them and a loop can: a person searching hard
 * does not type 30 distinct queries in a minute, while a component re-rendering on every keystroke
 * does. Refusal is a 429, which is retryable and says so, never a silent empty result.
 */
@Component
public class AiSpendRateLimiter {

    /** Maximum live buckets per guarded path. Same order as {@code AuthRateLimiter}. */
    private static final long MAX_BUCKETS_PER_CACHE = 10_000;

    /** Refill window shared by every budget below. */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Idle buckets are collected well after the window, never inside it. */
    private static final Duration EVICT_AFTER = Duration.ofMinutes(5);

    private final Cache<UUID, Bucket> searchBuckets;
    private final Cache<UUID, Bucket> liveAnalyzeBuckets;
    private final Cache<UUID, Bucket> reprocessBuckets;
    private final Cache<UUID, Bucket> splitPreviewBuckets;

    private final long searchPerMinute;
    private final long liveAnalyzePerMinute;
    private final long reprocessPerMinute;
    private final long splitPreviewPerMinute;

    public AiSpendRateLimiter(
            @Value("${nora.security.rate-limit.search-per-minute:30}") long searchPerMinute,
            @Value("${nora.security.rate-limit.live-analyze-per-minute:30}")
                    long liveAnalyzePerMinute,
            @Value("${nora.security.rate-limit.reprocess-per-minute:10}") long reprocessPerMinute,
            @Value("${nora.security.rate-limit.split-preview-per-minute:10}")
                    long splitPreviewPerMinute) {
        this.searchPerMinute = searchPerMinute;
        this.liveAnalyzePerMinute = liveAnalyzePerMinute;
        this.reprocessPerMinute = reprocessPerMinute;
        this.splitPreviewPerMinute = splitPreviewPerMinute;
        this.searchBuckets = buildCache();
        this.liveAnalyzeBuckets = buildCache();
        this.reprocessBuckets = buildCache();
        this.splitPreviewBuckets = buildCache();
    }

    private static Cache<UUID, Bucket> buildCache() {
        return Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS_PER_CACHE)
                .expireAfterAccess(EVICT_AFTER)
                .build();
    }

    /** {@code GET /meetings/search} and the MCP {@code search_meetings}: one embedding per call. */
    public boolean allowSearch(UUID userId) {
        return consume(searchBuckets, userId, searchPerMinute);
    }

    /** {@code POST /meetings/live-analyze}: one LLM call per transcript chunk. */
    public boolean allowLiveAnalyze(UUID userId) {
        return consume(liveAnalyzeBuckets, userId, liveAnalyzePerMinute);
    }

    /** {@code POST /meetings/{id}/reprocess}: re-runs the full analysis of a meeting. */
    public boolean allowReprocess(UUID userId) {
        return consume(reprocessBuckets, userId, reprocessPerMinute);
    }

    /** {@code POST /meetings/split-preview}: sends a whole transcript through the LLM. */
    public boolean allowSplitPreview(UUID userId) {
        return consume(splitPreviewBuckets, userId, splitPreviewPerMinute);
    }

    /**
     * A null principal is refused rather than waved through. Every guarded path is authenticated,
     * so a missing user id is a bug in the caller — and the safe reading of "I cannot tell who is
     * spending" is not "let them".
     */
    private boolean consume(Cache<UUID, Bucket> store, UUID userId, long capacity) {
        if (userId == null) {
            return false;
        }
        Bucket bucket =
                store.get(
                        userId,
                        id ->
                                Bucket.builder()
                                        .addLimit(Bandwidth.simple(capacity, WINDOW))
                                        .build());
        return bucket.tryConsume(1);
    }
}

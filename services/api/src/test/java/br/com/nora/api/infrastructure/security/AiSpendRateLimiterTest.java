package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The limiter in front of the request paths that spend money with an external provider.
 *
 * <p>What is worth asserting here is not "a counter counts". It is the three properties that make
 * the difference between a control and a decoration: the cap actually binds, one caller cannot
 * spend another caller's budget, and the four paths do not share one bucket — because a search loop
 * must not be able to lock a colleague out of live transcription.
 */
class AiSpendRateLimiterTest {

    /** Caps set low on purpose: the point is the boundary, not the number. */
    private static AiSpendRateLimiter limiter(long search, long live, long reprocess, long split) {
        return new AiSpendRateLimiter(search, live, reprocess, split);
    }

    @Test
    void refusesOnceTheBudgetIsSpent() {
        AiSpendRateLimiter rl = limiter(2, 2, 2, 2);
        UUID user = UUID.randomUUID();

        assertThat(rl.allowSearch(user)).isTrue();
        assertThat(rl.allowSearch(user)).isTrue();
        assertThat(rl.allowSearch(user)).isFalse();
    }

    @Test
    void oneCallerCannotSpendAnothersBudget() {
        AiSpendRateLimiter rl = limiter(1, 1, 1, 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(rl.allowSearch(first)).isTrue();
        assertThat(rl.allowSearch(first)).isFalse();
        // The second user's bucket is untouched: this is a per-principal budget, not a global one.
        assertThat(rl.allowSearch(second)).isTrue();
    }

    /**
     * Four buckets, not one. A single shared bucket would let the command palette — which fires on
     * typing — exhaust the budget a live capture needs to keep posting chunks, and the user would
     * experience it as transcription that stopped for no reason.
     */
    @Test
    void thePathsDoNotShareABucket() {
        AiSpendRateLimiter rl = limiter(1, 1, 1, 1);
        UUID user = UUID.randomUUID();

        assertThat(rl.allowSearch(user)).isTrue();
        assertThat(rl.allowSearch(user)).isFalse();

        assertThat(rl.allowLiveAnalyze(user)).isTrue();
        assertThat(rl.allowReprocess(user)).isTrue();
        assertThat(rl.allowSplitPreview(user)).isTrue();
    }

    /**
     * Every guarded path is authenticated, so a null principal is a bug in the caller. It is
     * refused rather than waved through: "I cannot tell who is spending" must not read as "let
     * them".
     */
    @Test
    void aMissingPrincipalIsRefusedRatherThanExempt() {
        AiSpendRateLimiter rl = limiter(100, 100, 100, 100);

        assertThat(rl.allowSearch(null)).isFalse();
        assertThat(rl.allowLiveAnalyze(null)).isFalse();
        assertThat(rl.allowReprocess(null)).isFalse();
        assertThat(rl.allowSplitPreview(null)).isFalse();
    }
}

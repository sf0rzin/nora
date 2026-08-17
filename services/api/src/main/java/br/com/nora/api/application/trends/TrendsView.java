package br.com.nora.api.application.trends;

import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * What {@code GET /trends} answers. Serialized straight to JSON, with no second DTO layer — same
 * shape {@code EmbeddingBackfillService.IndexStatus} uses for the backfill preview.
 *
 * @param granularity bucket size actually applied, which may differ from the one requested
 * @param timezone reporting zone the buckets were cut in, so a reader can reproduce the numbers
 * @param from start of the resolved range, inclusive
 * @param to end of the resolved range, exclusive
 * @param scopeStrategy how the authorization scope was resolved — see {@code TrendsController}
 * @param dataState what the panel is allowed to claim; the empty state reads this, not the counters
 * @param meetings meetings in the range that the caller is allowed to open
 * @param analysedMeetings how many of those carry a persisted analysis
 */
public record TrendsView(
        Granularity granularity,
        String timezone,
        OffsetDateTime from,
        OffsetDateTime to,
        ScopeStrategy scopeStrategy,
        DataState dataState,
        long meetings,
        long analysedMeetings,
        TaskLoad taskLoad,
        Themes themes) {

    /**
     * The three answers the panel must be able to tell apart. {@code NO_MEETINGS} and {@code
     * NO_ANALYSED_MEETINGS} both mean "there is nothing to draw"; {@code OK} with counters at zero
     * means "there really was no activity", which is a different sentence.
     */
    public enum DataState {
        NO_MEETINGS,
        NO_ANALYSED_MEETINGS,
        OK
    }

    /** Which of the two aggregate-authorization shapes produced these numbers. */
    public enum ScopeStrategy {
        /** The IAM decision was uniform over the tenant, so the aggregate ran tenant-wide. */
        TENANT_UNIFORM,
        /** Some statement distinguishes meetings, so the aggregate ran over the allowed set. */
        PER_MEETING_FILTER
    }

    /**
     * Task load. The series is windowed; the backlog counters are a snapshot at request time and
     * deliberately ignore the range, because "how many are open" is a state and not an interval.
     *
     * @param hasData false when the scope holds no action item at all — the difference between
     *     "nothing to show" and "a period with no activity"
     */
    public record TaskLoad(
            List<Point> buckets,
            long open,
            long overdue,
            Map<String, Long> byPriority,
            boolean hasData) {}

    /** One point of the task-load series. {@code bucketStart} is a local date in the zone above. */
    public record Point(LocalDate bucketStart, long opened, long completed) {}

    /** A theme and the number of distinct meetings it appeared in. */
    public record Theme(String label, long meetings) {}

    /**
     * Themes of one bucket. {@code sufficient} false means the bucket had too few analysed meetings
     * for a ranking to mean anything, and {@code items} is then empty on purpose.
     */
    public record ThemeBucket(
            LocalDate bucketStart, long meetings, boolean sufficient, List<Theme> items) {}

    /**
     * Recurring themes.
     *
     * @param source where the themes come from, so a later change of source is visible on the wire
     * @param matching how two spellings are decided to be the same theme
     * @param minimumMeetings analysed meetings below which no ranking is returned at all
     * @param sufficient whether the range cleared {@code minimumMeetings}
     */
    public record Themes(
            String source,
            String matching,
            int minimumMeetings,
            boolean sufficient,
            List<Theme> overall,
            List<ThemeBucket> buckets) {}
}

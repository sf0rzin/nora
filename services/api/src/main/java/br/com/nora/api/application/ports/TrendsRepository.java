package br.com.nora.api.application.ports;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only temporal aggregation behind the trends panel (US21). Every method answers for one
 * tenant and, optionally, for one explicit set of meetings — the set the caller is allowed to open.
 *
 * <p>The meeting restriction is not a filter for convenience: an aggregate computed only by {@code
 * WHERE tenant_id = ?} respects the tenant but not the per-item policy, so a user carrying a
 * conditional Deny over some meetings would see those meetings counted in a number they could never
 * drill into. {@link Scope} is what carries the answer to that, and the caller decides which of the
 * two shapes to build — see {@code TrendsService}.
 *
 * <p>Bucketing happens in SQL, in the reporting time zone the caller passes in {@link Window}, and
 * the bucket key comes back as a LOCAL date in that zone. Truncating in UTC would move a Friday
 * evening in Brazil into the following week.
 */
public interface TrendsRepository {

    /** Size of one bucket on the time axis. */
    enum Granularity {
        WEEK,
        MONTH;

        /** The unit {@code date_trunc} takes. ISO weeks, so a week starts on Monday. */
        public String sqlUnit() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Half-open reporting range {@code [from, to)} plus the bucketing rule applied to it. {@code
     * zone} is the reporting zone: it decides which bucket an instant near a boundary falls into,
     * and it is the zone the returned {@link BucketCount#bucketStart()} dates are expressed in.
     */
    record Window(OffsetDateTime from, OffsetDateTime to, Granularity granularity, ZoneId zone) {}

    /**
     * Which rows the aggregation may see. {@code meetingIds} null means "every meeting of the
     * tenant", which is only correct when the caller has already established that the authorization
     * decision is uniform across the tenant. A non-null list restricts the aggregate to exactly
     * those meetings, empty list included — an empty list must produce zeros, never the tenant.
     */
    record Scope(UUID tenantId, List<UUID> meetingIds) {

        /** Only safe when the IAM decision for {@code meeting:read} is uniform over the tenant. */
        public static Scope wholeTenant(UUID tenantId) {
            return new Scope(tenantId, null);
        }

        /** Restricts every aggregate to the meetings the caller may open. */
        public static Scope ofMeetings(UUID tenantId, List<UUID> meetingIds) {
            return new Scope(tenantId, List.copyOf(meetingIds));
        }

        public boolean restricted() {
            return meetingIds != null;
        }
    }

    /** One point of a counted series. {@code bucketStart} is a local date in the reporting zone. */
    record BucketCount(LocalDate bucketStart, long count) {}

    /**
     * Snapshot of the items that are not DONE, split by priority. Deliberately NOT windowed: a
     * backlog is a state at a point in time, and an "open tasks" figure restricted to a date range
     * would answer a question nobody asks.
     */
    record OpenByPriority(String priority, long open, long overdue) {}

    /** How many meetings are in the window, and how many of those carry a persisted analysis. */
    record MeetingCoverage(long meetings, long analysedMeetings) {}

    /**
     * One topic, as written by the analysis, attached to the meeting that carries it and to the
     * bucket that meeting falls into. Folding and counting happen in Java, so the normalisation
     * rule is one testable function rather than a database collation.
     */
    record TopicOccurrence(LocalDate bucketStart, UUID meetingId, String topic) {}

    /** Action items created inside the window, per bucket, keyed on {@code created_at}. */
    List<BucketCount> openedPerBucket(Scope scope, Window window);

    /** Action items completed inside the window, per bucket, keyed on {@code completed_at}. */
    List<BucketCount> completedPerBucket(Scope scope, Window window);

    /**
     * Current backlog: one row per priority present, with how many are open and how many of those
     * are past their due date on {@code today} (a local date in the reporting zone).
     */
    List<OpenByPriority> openBacklog(Scope scope, LocalDate today);

    /** Meetings in the window and how many are analysed — the input to the empty-state decision. */
    MeetingCoverage meetingCoverage(Scope scope, Window window);

    /**
     * Analysed meetings per bucket. Counted separately from {@link #topicOccurrences} on purpose:
     * an analysis with an empty {@code topics} array still makes its bucket denser, and a bucket's
     * "is this enough to rank?" answer must not depend on whether the model happened to emit any.
     */
    List<BucketCount> analysedMeetingsPerBucket(Scope scope, Window window);

    /** Every {@code meeting_analyses.topics} entry of the window, one row per (meeting, topic). */
    List<TopicOccurrence> topicOccurrences(Scope scope, Window window);
}

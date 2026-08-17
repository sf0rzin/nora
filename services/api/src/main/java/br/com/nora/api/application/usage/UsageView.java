package br.com.nora.api.application.usage;

import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What {@code GET /usage} answers (US33): the tenant's own consumption over a period. Serialized
 * straight to JSON with no second DTO layer, the same shape {@code TrendsView} uses.
 *
 * <p>The two halves come from two different databases and have very different guarantees. Meetings
 * and analyses are the tenant's own rows in the primary database, authorized item by item. The AI
 * half comes from the control plane's {@code usage_events} (ADR 0022), which is optional
 * infrastructure — hence {@link Ai#state()}, which says whether the numbers are a measurement, an
 * absence of measurement, or a deliberate refusal.
 *
 * @param granularity bucket size actually applied, which may differ from the one requested
 * @param timezone reporting zone the buckets were cut in, so a reader can reproduce the numbers
 * @param from start of the resolved range, inclusive
 * @param to end of the resolved range, exclusive
 * @param scopeStrategy how the authorization scope was resolved — see {@code UsageController}
 * @param dataState what the screen is allowed to claim; the empty state reads this, not the
 *     counters
 * @param meetings meetings in the range that the caller is allowed to open
 * @param analysedMeetings how many of those carry a persisted analysis
 */
public record UsageView(
        Granularity granularity,
        String timezone,
        OffsetDateTime from,
        OffsetDateTime to,
        ScopeStrategy scopeStrategy,
        DataState dataState,
        long meetings,
        long analysedMeetings,
        List<MeetingBucket> meetingBuckets,
        Ai ai) {

    /** Cost basis: provider list price from the model catalog, never an invoiced amount. */
    public static final String COST_BASIS_CATALOG = "CATALOG_LIST_PRICE";

    /**
     * The three answers the screen must be able to tell apart. {@code OK} with a counter at zero
     * means a real zero; the other two mean there is nothing to draw, and for different reasons.
     *
     * <p>When {@link Ai#state()} is not {@link AiState#AVAILABLE} this is computed over the
     * meetings half alone, because an absent measurement is not a zero and must not be counted as
     * one.
     */
    public enum DataState {
        /** No meeting in the range and no AI call this API can see — nothing has happened yet. */
        NO_DATA,
        /** Meetings in the range, none of them analysed, and no AI call either. */
        NO_ANALYSED_MEETINGS,
        /** There is something to show. */
        OK
    }

    /** Which of the two aggregate-authorization shapes produced these numbers. */
    public enum ScopeStrategy {
        /** The IAM decision was uniform over the tenant, so the aggregate ran tenant-wide. */
        TENANT_UNIFORM,
        /** Some statement distinguishes meetings, so the aggregate ran over the allowed set. */
        PER_MEETING_FILTER
    }

    /** Whether the AI half is a measurement, an absence of one, or a refusal. */
    public enum AiState {
        /** The control plane answered; the numbers below are that answer. */
        AVAILABLE,
        /** The control plane is off or degraded (ADR 0022). Zeros here mean "unknown". */
        UNAVAILABLE,
        /**
         * Withheld on purpose. {@code usage_events} carries no meeting id, so a caller whose IAM
         * position distinguishes meetings cannot be shown a tenant-wide AI total without disclosing
         * activity from meetings they may not open.
         */
        WITHHELD_RESTRICTED_SCOPE
    }

    /** Meetings of one bucket, and how many of them carry an analysis. */
    public record MeetingBucket(LocalDate bucketStart, long meetings, long analysedMeetings) {}

    /**
     * AI consumption over the range.
     *
     * @param costBasis where the money figure comes from; {@link #COST_BASIS_CATALOG} today, which
     *     means catalog list price multiplied by measured tokens and never an invoiced amount
     * @param currency ISO 4217 code of {@code costUsd}; always USD, the currency the model catalog
     *     prices are written in
     * @param calls events recorded in the range, failures included
     * @param unmeteredCalls how many of {@code calls} came from a service whose cost is not
     *     measurable, so a reader can see how much of the total the money figure does not cover
     * @param costUsd sum of the per-event costs, each computed from catalog list price × tokens
     */
    public record Ai(
            AiState state,
            String costBasis,
            String currency,
            long calls,
            long unmeteredCalls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd,
            List<ServiceUsage> byService,
            List<AiBucket> buckets) {

        /** The empty answer, carrying the reason it is empty rather than a bare set of zeros. */
        public static Ai empty(AiState state) {
            return new Ai(
                    state,
                    COST_BASIS_CATALOG,
                    "USD",
                    0,
                    0,
                    0,
                    0,
                    BigDecimal.ZERO,
                    List.of(),
                    List.of());
        }
    }

    /**
     * One service's slice of the range.
     *
     * @param metered false when this service's events carry no token count by construction, so its
     *     zero cost is a property of the design and not an observation — {@code stt} is the case
     *     that exists today (ADR 0045: what is recorded is a session issued, not a minute
     *     transcribed)
     */
    public record ServiceUsage(
            String service,
            boolean metered,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {}

    /** One point of the AI series. {@code bucketStart} is a local date in the reporting zone. */
    public record AiBucket(
            LocalDate bucketStart,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {}
}

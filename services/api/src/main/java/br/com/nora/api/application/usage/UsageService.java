package br.com.nora.api.application.usage;

import br.com.nora.api.application.platform.UsageEventRepository;
import br.com.nora.api.application.platform.UsageEventRepository.TenantUsageRow;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.TrendsRepository;
import br.com.nora.api.application.ports.TrendsRepository.BucketCount;
import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.application.ports.TrendsRepository.MeetingCoverage;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import br.com.nora.api.application.ports.TrendsRepository.Window;
import br.com.nora.api.application.reporting.ReportingWindow;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Tenant usage panel (US33) — what one tenant consumed over a period, answered to that tenant.
 *
 * <p>The pipeline this reads was already built and only had an operator-facing end: {@code
 * UsageRecorder} writes every external AI call with tenant, service, provider, model, tokens, cost
 * and status, and {@code GET /admin/platform/telemetry/business} reads it for the platform owner.
 * What did not exist is the half where a tenant sees their own line of it.
 *
 * <p><b>Two databases, two very different guarantees.</b> Meetings and analyses are the tenant's
 * own rows in the primary database, and the aggregate is restricted to the meetings the caller may
 * open. AI events live in the control plane (ADR 0022), which is optional infrastructure: disabled
 * in local, test and CI, and degradable in production. That is why the AI half carries a state
 * instead of a number that would silently read as zero when the truth is "not known".
 *
 * <p><b>No transaction spans both.</b> Every primary read is already {@code @Transactional} in its
 * own adapter, so this method deliberately does not wrap them in one: holding a primary connection
 * open while querying a second, possibly degraded, database is the coupling ADR 0022 §4 exists to
 * avoid. The cost is that the primary counters are not one snapshot, which for a period report is
 * not a difference anyone can observe.
 */
@Service
public class UsageService {

    private static final Logger LOG = LoggerFactory.getLogger(UsageService.class);

    /** Reporting zone, shared with the trends panel — see {@link ReportingWindow}. */
    public static final ZoneId REPORTING_ZONE = ReportingWindow.REPORTING_ZONE;

    /**
     * Services whose events carry no token count by construction, so their cost is structurally
     * zero rather than measured as zero. {@code stt} is the one that exists: with the
     * ephemeral-token design of ADR 0039/0045 the audio never crosses NORA's infrastructure, so
     * what is recorded is a session issued and not a minute transcribed. A cost derived from a
     * client-reported duration would be a fabricated number, and ADR 0045 §4 refuses to produce
     * one.
     */
    static final Set<String> UNMETERED_SERVICES = Set.of("stt");

    static final int DEFAULT_WEEK_BUCKETS = 12;
    static final int DEFAULT_MONTH_BUCKETS = 6;
    static final int MAX_WEEK_BUCKETS = 52;
    static final int MAX_MONTH_BUCKETS = 24;

    private final TrendsRepository meetings;
    private final ObjectProvider<UsageEventRepository> usageEvents;
    private final PlatformAvailability platform;
    private final Clock clock;

    public UsageService(
            TrendsRepository meetings,
            ObjectProvider<UsageEventRepository> usageEvents,
            PlatformAvailability platform,
            Clock clock) {
        this.meetings = meetings;
        // Absent whenever nora.platform.enabled is false, which is the default everywhere but the
        // deployed stack. Resolved per call rather than at construction so a bean that appears
        // later is picked up, and so the panel does not fail to start when the module is off.
        this.usageEvents = usageEvents;
        this.platform = platform;
        this.clock = clock;
    }

    /** Builds the panel for one already-authorized scope. See {@code UsageController} for how. */
    public UsageView compute(
            Scope scope, Granularity granularity, OffsetDateTime from, OffsetDateTime to) {
        Window window = resolveWindow(granularity, from, to);
        List<LocalDate> axis = ReportingWindow.axis(window);

        MeetingCoverage coverage = meetings.meetingCoverage(scope, window);
        Map<LocalDate, Long> held = index(meetings.meetingsPerBucket(scope, window));
        Map<LocalDate, Long> analysed = index(meetings.analysedMeetingsPerBucket(scope, window));

        List<UsageView.MeetingBucket> meetingBuckets = new ArrayList<>(axis.size());
        for (LocalDate bucket : axis) {
            meetingBuckets.add(
                    new UsageView.MeetingBucket(
                            bucket,
                            held.getOrDefault(bucket, 0L),
                            analysed.getOrDefault(bucket, 0L)));
        }

        UsageView.Ai ai = ai(scope, window, axis);

        return new UsageView(
                window.granularity(),
                REPORTING_ZONE.getId(),
                window.from(),
                window.to(),
                scope.restricted()
                        ? UsageView.ScopeStrategy.PER_MEETING_FILTER
                        : UsageView.ScopeStrategy.TENANT_UNIFORM,
                dataState(coverage, ai),
                coverage.meetings(),
                coverage.analysedMeetings(),
                List.copyOf(meetingBuckets),
                ai);
    }

    /* ================================ AI half ================================ */

    /**
     * The control plane's half, or the reason there is not one.
     *
     * <p>A restricted scope is refused rather than approximated. {@code usage_events} carries no
     * meeting id — it is denormalized for the operator's dimensions (tenant, model, service) — so
     * there is no way to answer "how many analyses ran for the meetings this caller may open". The
     * tenant-wide figure would be an answer about the meetings they may not, and a count is
     * information: this is the same reasoning the trends panel applies item by item, reaching the
     * only conclusion available when the item cannot be identified at all.
     */
    private UsageView.Ai ai(Scope scope, Window window, List<LocalDate> axis) {
        if (scope.restricted()) {
            return UsageView.Ai.empty(UsageView.AiState.WITHHELD_RESTRICTED_SCOPE);
        }
        UsageEventRepository repo = usageEvents.getIfAvailable();
        if (repo == null || !platform.isUsable()) {
            return UsageView.Ai.empty(UsageView.AiState.UNAVAILABLE);
        }
        List<TenantUsageRow> rows;
        try {
            rows =
                    repo.tenantSeries(
                            scope.tenantId(),
                            window.from(),
                            window.to(),
                            window.granularity(),
                            window.zone());
        } catch (RuntimeException ex) {
            // Soft-fail, like every other read of the control plane: the tenant's own screen must
            // not 500 because the platform database is unreachable.
            LOG.warn("Tenant usage: control plane read failed: {}", ex.getMessage());
            return UsageView.Ai.empty(UsageView.AiState.UNAVAILABLE);
        }
        return fold(rows, axis);
    }

    /** Folds the (bucket, service) cells into the two projections the screen draws. */
    private static UsageView.Ai fold(List<TenantUsageRow> rows, List<LocalDate> axis) {
        Map<String, long[]> serviceCounts = new HashMap<>();
        Map<String, BigDecimal> serviceCost = new HashMap<>();
        Map<LocalDate, long[]> bucketCounts = new HashMap<>();
        Map<LocalDate, BigDecimal> bucketCost = new HashMap<>();

        long calls = 0;
        long unmetered = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;

        for (TenantUsageRow row : rows) {
            BigDecimal rowCost = row.costUsd() == null ? BigDecimal.ZERO : row.costUsd();
            calls += row.calls();
            promptTokens += row.promptTokens();
            completionTokens += row.completionTokens();
            cost = cost.add(rowCost);
            if (UNMETERED_SERVICES.contains(row.service())) {
                unmetered += row.calls();
            }
            accumulate(serviceCounts, serviceCost, row.service(), row, rowCost);
            if (row.bucketStart() != null) {
                accumulate(bucketCounts, bucketCost, row.bucketStart(), row, rowCost);
            }
        }

        List<UsageView.ServiceUsage> byService = new ArrayList<>(serviceCounts.size());
        for (Map.Entry<String, long[]> entry : serviceCounts.entrySet()) {
            long[] c = entry.getValue();
            byService.add(
                    new UsageView.ServiceUsage(
                            entry.getKey(),
                            !UNMETERED_SERVICES.contains(entry.getKey()),
                            c[0],
                            c[1],
                            c[2],
                            serviceCost.getOrDefault(entry.getKey(), BigDecimal.ZERO)));
        }
        // Busiest first, ties broken by name so the same data always renders the same way.
        byService.sort(
                Comparator.comparingLong((UsageView.ServiceUsage s) -> s.calls())
                        .reversed()
                        .thenComparing(UsageView.ServiceUsage::service));

        // Dense axis: a period with no call is a zero on the chart, not a missing point the eye
        // joins to its neighbours.
        List<UsageView.AiBucket> buckets = new ArrayList<>(axis.size());
        for (LocalDate bucket : axis) {
            long[] c = bucketCounts.getOrDefault(bucket, new long[3]);
            buckets.add(
                    new UsageView.AiBucket(
                            bucket,
                            c[0],
                            c[1],
                            c[2],
                            bucketCost.getOrDefault(bucket, BigDecimal.ZERO)));
        }

        return new UsageView.Ai(
                UsageView.AiState.AVAILABLE,
                UsageView.COST_BASIS_CATALOG,
                "USD",
                calls,
                unmetered,
                promptTokens,
                completionTokens,
                cost,
                List.copyOf(byService),
                List.copyOf(buckets));
    }

    private static <K> void accumulate(
            Map<K, long[]> counts,
            Map<K, BigDecimal> cost,
            K key,
            TenantUsageRow row,
            BigDecimal rowCost) {
        long[] slot = counts.computeIfAbsent(key, k -> new long[3]);
        slot[0] += row.calls();
        slot[1] += row.promptTokens();
        slot[2] += row.completionTokens();
        cost.merge(key, rowCost, BigDecimal::add);
    }

    /* ================================ window ================================= */

    /**
     * The empty state, decided over both halves. A tenant that never uploaded a meeting but used
     * the chat has AI calls and no meetings, and telling them "no data yet" would be false; a
     * tenant with meetings and no analysis has a real reason for the zeros that is not "no
     * activity".
     *
     * <p>When the AI half is not {@code AVAILABLE} its zeros are ignorance and not measurement, so
     * only the meetings half decides.
     */
    private static UsageView.DataState dataState(MeetingCoverage coverage, UsageView.Ai ai) {
        boolean aiKnown = ai.state() == UsageView.AiState.AVAILABLE && ai.calls() > 0;
        if (coverage.meetings() == 0 && !aiKnown) {
            return UsageView.DataState.NO_DATA;
        }
        if (coverage.analysedMeetings() == 0 && !aiKnown) {
            return UsageView.DataState.NO_ANALYSED_MEETINGS;
        }
        return UsageView.DataState.OK;
    }

    /**
     * Resolves the reporting range through {@link ReportingWindow}. The default granularity here is
     * {@code MONTH} and not {@code WEEK}: consumption is read against a billing period, and a
     * twelve-week default would answer a question about tempo instead.
     */
    private Window resolveWindow(Granularity granularity, OffsetDateTime from, OffsetDateTime to) {
        Granularity g = granularity == null ? Granularity.MONTH : granularity;
        int defaults = g == Granularity.WEEK ? DEFAULT_WEEK_BUCKETS : DEFAULT_MONTH_BUCKETS;
        int ceiling = g == Granularity.WEEK ? MAX_WEEK_BUCKETS : MAX_MONTH_BUCKETS;
        return ReportingWindow.resolve(clock.now(), g, from, to, defaults, ceiling);
    }

    private static Map<LocalDate, Long> index(List<BucketCount> rows) {
        Map<LocalDate, Long> out = new HashMap<>();
        for (BucketCount row : rows) {
            out.merge(row.bucketStart(), row.count(), Long::sum);
        }
        return out;
    }
}

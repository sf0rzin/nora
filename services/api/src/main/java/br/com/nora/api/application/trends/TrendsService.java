package br.com.nora.api.application.trends;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.TrendsRepository;
import br.com.nora.api.application.ports.TrendsRepository.BucketCount;
import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.application.ports.TrendsRepository.MeetingCoverage;
import br.com.nora.api.application.ports.TrendsRepository.OpenByPriority;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import br.com.nora.api.application.ports.TrendsRepository.TopicOccurrence;
import br.com.nora.api.application.ports.TrendsRepository.Window;
import br.com.nora.api.application.reporting.ReportingWindow;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporal aggregation behind the trends panel (US21). Two halves with very different costs, and
 * neither of them calls a model.
 *
 * <p><b>Task load is relational.</b> Opened per bucket, completed per bucket, the open backlog and
 * its priority split all come out of {@code meeting_action_items} with GROUP BY. Involving an LLM
 * in counting rows would be expensive, non-deterministic and pointless.
 *
 * <p><b>Themes come from {@code meeting_analyses.topics}</b>, which the analysis already extracted
 * and persisted. Two alternatives were on the table and are not what shipped: clustering the
 * embeddings of {@code meeting_embeddings}, which needs an LLM to name a cluster and does not scale
 * past a few hundred meetings because the cosine runs in Java over JSON-in-TEXT vectors; and asking
 * the model for themes over a batch of summaries, which costs money per query and is not
 * reproducible between two runs of the same question. Reading topics costs one query and nothing
 * else, and it makes the panel independent of whether the tenant's RAG backfill (ADR 0044) has been
 * run. The source is reported on the wire ({@code themes.source}) so replacing it later is a
 * visible change and not a silent one.
 *
 * <p><b>Authorization is not this class's business</b> — it arrives already resolved as a {@link
 * Scope}. See {@code TrendsController} for which of the two shapes is built and why.
 *
 * <p><b>Few points are noise.</b> A ranking over three meetings is a list, not a trend, so the
 * themes half refuses to answer below {@link #MIN_ANALYSED_MEETINGS} in the range and marks each
 * bucket below {@link #MIN_MEETINGS_PER_BUCKET} as insufficient rather than drawing it.
 */
@Service
public class TrendsService {

    /**
     * Reporting zone, fixed, and shared with every other panel that reports over a period — see
     * {@link ReportingWindow#REPORTING_ZONE} for why it is not UTC. Kept as a field here because it
     * is what this class quotes on the wire.
     */
    public static final ZoneId REPORTING_ZONE = ReportingWindow.REPORTING_ZONE;

    /** Analysed meetings in the range below which no theme ranking is produced at all. */
    public static final int MIN_ANALYSED_MEETINGS = 5;

    /** Analysed meetings in one bucket below which that bucket's ranking is withheld. */
    public static final int MIN_MEETINGS_PER_BUCKET = 3;

    /** Where the themes come from, echoed on the wire so a change of source is not silent. */
    static final String THEME_SOURCE = "ANALYSIS_TOPICS";

    /** How two spellings are decided to be the same theme: folded strings, never semantics. */
    static final String THEME_MATCHING = "LEXICAL";

    static final int DEFAULT_WEEK_BUCKETS = 12;
    static final int DEFAULT_MONTH_BUCKETS = 6;
    static final int MAX_WEEK_BUCKETS = 52;
    static final int MAX_MONTH_BUCKETS = 24;
    static final int TOP_THEMES_OVERALL = 10;
    static final int TOP_THEMES_PER_BUCKET = 5;

    /** Fixed order, always all three, so the panel never has to guess a missing priority. */
    private static final List<String> PRIORITIES = List.of("HIGH", "MEDIUM", "LOW");

    private final TrendsRepository trends;
    private final Clock clock;

    public TrendsService(TrendsRepository trends, Clock clock) {
        this.trends = trends;
        this.clock = clock;
    }

    /**
     * Builds the panel for one already-authorized scope. {@code from}/{@code to} are optional; an
     * absent, inverted or over-long range is resolved into a sane one and the result says which.
     */
    @Transactional(readOnly = true)
    public TrendsView compute(
            Scope scope, Granularity granularity, OffsetDateTime from, OffsetDateTime to) {
        Window window = resolveWindow(granularity, from, to);
        List<LocalDate> axis = ReportingWindow.axis(window);
        LocalDate today = LocalDate.ofInstant(clock.now(), REPORTING_ZONE);

        MeetingCoverage coverage = trends.meetingCoverage(scope, window);
        TrendsView.DataState dataState = dataState(coverage);

        return new TrendsView(
                window.granularity(),
                REPORTING_ZONE.getId(),
                window.from(),
                window.to(),
                scope.restricted()
                        ? TrendsView.ScopeStrategy.PER_MEETING_FILTER
                        : TrendsView.ScopeStrategy.TENANT_UNIFORM,
                dataState,
                coverage.meetings(),
                coverage.analysedMeetings(),
                taskLoad(scope, window, axis, today),
                themes(scope, window, axis, coverage));
    }

    /* ============================== task load ============================== */

    private TrendsView.TaskLoad taskLoad(
            Scope scope, Window window, List<LocalDate> axis, LocalDate today) {
        Map<LocalDate, Long> opened = index(trends.openedPerBucket(scope, window));
        Map<LocalDate, Long> completed = index(trends.completedPerBucket(scope, window));

        List<TrendsView.Point> points = new ArrayList<>(axis.size());
        boolean anyMovement = false;
        for (LocalDate bucket : axis) {
            long o = opened.getOrDefault(bucket, 0L);
            long c = completed.getOrDefault(bucket, 0L);
            anyMovement = anyMovement || o > 0 || c > 0;
            points.add(new TrendsView.Point(bucket, o, c));
        }

        List<OpenByPriority> backlog = trends.openBacklog(scope, today);
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (String priority : PRIORITIES) {
            byPriority.put(priority, 0L);
        }
        long open = 0;
        long overdue = 0;
        for (OpenByPriority row : backlog) {
            byPriority.merge(row.priority(), row.open(), Long::sum);
            open += row.open();
            overdue += row.overdue();
        }
        return new TrendsView.TaskLoad(
                List.copyOf(points), open, overdue, byPriority, anyMovement || open > 0);
    }

    /* ================================ themes =============================== */

    private TrendsView.Themes themes(
            Scope scope, Window window, List<LocalDate> axis, MeetingCoverage coverage) {
        boolean sufficient = coverage.analysedMeetings() >= MIN_ANALYSED_MEETINGS;
        Map<LocalDate, Long> perBucket = index(trends.analysedMeetingsPerBucket(scope, window));

        if (!sufficient) {
            // Below the floor nothing is ranked, not even the buckets that would individually
            // clear it: a ranking the caller cannot act on is worse than an explicit refusal.
            List<TrendsView.ThemeBucket> empty = new ArrayList<>(axis.size());
            for (LocalDate bucket : axis) {
                empty.add(
                        new TrendsView.ThemeBucket(
                                bucket, perBucket.getOrDefault(bucket, 0L), false, List.of()));
            }
            return new TrendsView.Themes(
                    THEME_SOURCE,
                    THEME_MATCHING,
                    MIN_ANALYSED_MEETINGS,
                    false,
                    List.of(),
                    List.copyOf(empty));
        }

        // key -> surface form -> how often that spelling was written, for the displayed label.
        Map<String, Map<String, Long>> surfaces = new HashMap<>();
        // key -> the distinct meetings carrying it, over the whole range and per bucket. Distinct
        // meetings rather than raw occurrences: one meeting listing a subject twice under two
        // spellings must not count as two.
        Map<String, Set<UUID>> overallMeetings = new HashMap<>();
        Map<LocalDate, Map<String, Set<UUID>>> bucketMeetings = new HashMap<>();

        for (TopicOccurrence occurrence : trends.topicOccurrences(scope, window)) {
            String key = TopicNormalizer.fold(occurrence.topic());
            if (key.isEmpty()) {
                continue;
            }
            surfaces.computeIfAbsent(key, k -> new HashMap<>())
                    .merge(occurrence.topic().trim(), 1L, Long::sum);
            overallMeetings.computeIfAbsent(key, k -> new HashSet<>()).add(occurrence.meetingId());
            bucketMeetings
                    .computeIfAbsent(occurrence.bucketStart(), k -> new HashMap<>())
                    .computeIfAbsent(key, k -> new HashSet<>())
                    .add(occurrence.meetingId());
        }

        List<TrendsView.ThemeBucket> buckets = new ArrayList<>(axis.size());
        for (LocalDate bucket : axis) {
            long meetings = perBucket.getOrDefault(bucket, 0L);
            boolean enough = meetings >= MIN_MEETINGS_PER_BUCKET;
            List<TrendsView.Theme> items =
                    enough
                            ? rank(
                                    bucketMeetings.getOrDefault(bucket, Map.of()),
                                    surfaces,
                                    TOP_THEMES_PER_BUCKET)
                            : List.of();
            buckets.add(new TrendsView.ThemeBucket(bucket, meetings, enough, items));
        }

        return new TrendsView.Themes(
                THEME_SOURCE,
                THEME_MATCHING,
                MIN_ANALYSED_MEETINGS,
                true,
                rank(overallMeetings, surfaces, TOP_THEMES_OVERALL),
                List.copyOf(buckets));
    }

    /** Most-mentioned first, ties broken by label so the same data always renders the same way. */
    private static List<TrendsView.Theme> rank(
            Map<String, Set<UUID>> byKey, Map<String, Map<String, Long>> surfaces, int limit) {
        List<TrendsView.Theme> out = new ArrayList<>(byKey.size());
        for (Map.Entry<String, Set<UUID>> entry : byKey.entrySet()) {
            String label = bestLabel(surfaces.get(entry.getKey()), entry.getKey());
            out.add(new TrendsView.Theme(label, entry.getValue().size()));
        }
        out.sort(
                Comparator.comparingLong((TrendsView.Theme t) -> t.meetings())
                        .reversed()
                        .thenComparing(TrendsView.Theme::label));
        return out.size() <= limit ? List.copyOf(out) : List.copyOf(out.subList(0, limit));
    }

    /** The spelling written most often; the alphabetically first one when two tie. */
    private static String bestLabel(Map<String, Long> spellings, String fallback) {
        if (spellings == null || spellings.isEmpty()) {
            return fallback;
        }
        String best = null;
        long bestCount = -1;
        for (Map.Entry<String, Long> entry : spellings.entrySet()) {
            String spelling = entry.getKey();
            long count = entry.getValue();
            if (best != null && count < bestCount) {
                continue;
            }
            if (best != null && count == bestCount && spelling.compareTo(best) >= 0) {
                continue;
            }
            best = spelling;
            bestCount = count;
        }
        return best == null || best.isBlank() ? fallback : best;
    }

    /* ================================ window =============================== */

    private static TrendsView.DataState dataState(MeetingCoverage coverage) {
        if (coverage.meetings() == 0) {
            return TrendsView.DataState.NO_MEETINGS;
        }
        if (coverage.analysedMeetings() == 0) {
            return TrendsView.DataState.NO_ANALYSED_MEETINGS;
        }
        return TrendsView.DataState.OK;
    }

    /**
     * Resolves the reporting range through {@link ReportingWindow}, with this panel's own default
     * span and ceiling. The rules — absent {@code to} means now, an inverted range falls back to
     * the default, an over-long one is clamped forward — live there and are shared with the usage
     * panel.
     */
    private Window resolveWindow(Granularity granularity, OffsetDateTime from, OffsetDateTime to) {
        Granularity g = granularity == null ? Granularity.WEEK : granularity;
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

package br.com.nora.api.application.reporting;

import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.application.ports.TrendsRepository.Window;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Range resolution and bucket axis shared by every panel that reports over a period — the trends
 * panel (US21) and the tenant usage panel (US33).
 *
 * <p>It exists because the alternative was two copies of the reporting time zone. Two panels that
 * cut weeks in different zones would disagree about which week a Friday-evening meeting belongs to,
 * and the disagreement would show up as two screens quoting different numbers for the same tenant
 * with no way to tell which one is wrong.
 *
 * <p>Nothing here knows about authorization or about what is being counted. It answers two
 * questions: which half-open range a request actually asked for, and which buckets that range
 * contains.
 */
public final class ReportingWindow {

    /**
     * Reporting zone, fixed. Bucketing a {@code TIMESTAMPTZ} by week with no declared zone means
     * bucketing in UTC, which files a Friday 21:00 meeting in Brazil under the following week. The
     * product's users and its copy are pt-BR, so the zone is the country's, not the server's, and
     * it is returned in every payload so the numbers can be reproduced.
     */
    public static final ZoneId REPORTING_ZONE = ZoneId.of("America/Sao_Paulo");

    private ReportingWindow() {}

    /**
     * Resolves the reporting range. An absent {@code to} is now; an absent, inverted or empty
     * {@code from} falls back to {@code defaultBuckets}; a range longer than {@code maxBuckets} is
     * clamped forward, so no request can ask the database for an unbounded series.
     */
    public static Window resolve(
            Instant now,
            Granularity granularity,
            OffsetDateTime from,
            OffsetDateTime to,
            int defaultBuckets,
            int maxBuckets) {
        Granularity g = granularity == null ? Granularity.WEEK : granularity;
        OffsetDateTime end = to == null ? OffsetDateTime.ofInstant(now, REPORTING_ZONE) : to;

        OffsetDateTime start = from == null ? back(end, g, defaultBuckets - 1) : from;
        if (!start.isBefore(end)) {
            start = back(end, g, defaultBuckets - 1);
        }
        OffsetDateTime floor = back(end, g, maxBuckets - 1);
        if (start.isBefore(floor)) {
            start = floor;
        }
        return new Window(start, end, g, REPORTING_ZONE);
    }

    /**
     * Every bucket of the range, in order and with no gaps, so a period with no activity is a zero
     * on the chart instead of a missing point the eye joins to its neighbours.
     */
    public static List<LocalDate> axis(Window window) {
        LocalDate first =
                truncate(
                        window.from().atZoneSameInstant(window.zone()).toLocalDate(),
                        window.granularity());
        // The last instant INSIDE the half-open range: `to` itself belongs to the next bucket when
        // it lands exactly on a boundary.
        LocalDate last =
                truncate(
                        window.to().minusNanos(1).atZoneSameInstant(window.zone()).toLocalDate(),
                        window.granularity());
        List<LocalDate> out = new ArrayList<>();
        LocalDate cursor = first;
        while (!cursor.isAfter(last)) {
            out.add(cursor);
            cursor =
                    window.granularity() == Granularity.WEEK
                            ? cursor.plusWeeks(1)
                            : cursor.plusMonths(1);
        }
        return List.copyOf(out);
    }

    /** Same truncation Postgres applies: ISO week (Monday) or the first day of the month. */
    public static LocalDate truncate(LocalDate date, Granularity granularity) {
        return granularity == Granularity.WEEK
                ? date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : date.withDayOfMonth(1);
    }

    private static OffsetDateTime back(OffsetDateTime from, Granularity g, int units) {
        return g == Granularity.WEEK ? from.minusWeeks(units) : from.minusMonths(units);
    }
}

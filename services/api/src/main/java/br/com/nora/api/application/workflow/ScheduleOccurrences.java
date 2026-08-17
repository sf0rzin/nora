package br.com.nora.api.application.workflow;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Answers the one temporal question a scheduled flow asks: given the expression a {@link
 * br.com.nora.api.domain.workflow.ScheduleSpec} compiled to, when is the next occurrence?
 *
 * <p>The arithmetic is Spring's {@link CronExpression}, not ours. Hand-rolling "the next Monday at
 * 09:00 in this zone" is the kind of code that is right for two years and then meets a clock
 * change.
 *
 * <p><b>The zone is fixed at {@code America/Sao_Paulo}</b>, the same constant the trends panel uses
 * (architecture §19) and the same one the two calendar actions write events in. A schedule differs
 * from a report only in that getting the zone wrong is louder: "daily at 09:00" evaluated in UTC
 * arrives at 06:00 local, and nobody reads that as a timezone bug — they read it as a product that
 * fires at random. It is stored per row in {@code workflow_schedules.timezone} rather than assumed,
 * so introducing a per-tenant zone later is a backfill and not an archaeology exercise (ADR 0047
 * §3).
 */
@Component
public class ScheduleOccurrences {

    /** Zone every occurrence is computed in. Same value as {@code TrendsService.REPORTING_ZONE}. */
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    /** IANA id of {@link #ZONE}, as persisted on the schedule row. */
    public String zoneId() {
        return ZONE.getId();
    }

    /**
     * The first occurrence of {@code cron} strictly after {@code from}, or null when the expression
     * cannot be parsed or has no further occurrence.
     *
     * <p>Null is only reachable for a row that bypassed the API: {@code WorkflowDefinitionParser}
     * is the single writer of this column and it only ever writes the three shapes the vocabulary
     * compiles to. The caller logs it at ERROR and leaves the row alone, which is loud on purpose.
     */
    public OffsetDateTime nextAfter(String cron, OffsetDateTime from) {
        try {
            ZonedDateTime next = CronExpression.parse(cron).next(from.atZoneSameInstant(ZONE));
            return next == null ? null : next.toOffsetDateTime();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}

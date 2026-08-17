package br.com.nora.api.domain.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Run state of a {@link TriggerType#SCHEDULE_CRON} flow (V032, ADR 0047). One row per scheduled
 * workflow, written on save and read by the scheduler tick. Immutable.
 *
 * <p>{@code nextFireAt} and {@code windowFrom} look redundant and are not — they are what splits
 * catch-up in two (ADR 0047 §4):
 *
 * <ul>
 *   <li><b>{@code nextFireAt}</b> is advanced AT CLAIM, to the next occurrence strictly after now.
 *       That is what makes a six-hour outage's three missed occurrences fire ONCE on recovery
 *       instead of three times.
 *   <li><b>{@code windowFrom}</b> is advanced AT RELEASE, to the instant the completed run fired.
 *       It is the lower bound on {@code meeting_analyses.generated_at} the next run reads, so a run
 *       that dies mid-flight does not take its meetings down with it.
 * </ul>
 *
 * <p>Occurrences are therefore at-most-once and meetings at-least-once, and the two values
 * diverging is the visible evidence that a run died between claim and release.
 *
 * <p>{@code claimedAt} is the overlap guard: a due row that already carries one is skipped, not
 * queued. {@code claimOwner} is the id of the process holding it — diagnostic only, since the
 * correctness comes from the compare-and-swap in the claiming UPDATE, but it is what tells an
 * operator which container is running a schedule on a day two of them are up.
 */
public record WorkflowSchedule(
        UUID workflowId,
        UUID tenantId,
        String cron,
        String timezone,
        OffsetDateTime nextFireAt,
        OffsetDateTime windowFrom,
        OffsetDateTime lastFireAt,
        OffsetDateTime claimedAt,
        String claimOwner) {

    public WorkflowSchedule {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron is required");
        }
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone is required");
        }
        if (nextFireAt == null) {
            throw new IllegalArgumentException("nextFireAt is required");
        }
        if (windowFrom == null) {
            throw new IllegalArgumentException("windowFrom is required");
        }
    }
}

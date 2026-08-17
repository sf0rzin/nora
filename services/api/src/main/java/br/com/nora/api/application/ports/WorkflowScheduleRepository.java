package br.com.nora.api.application.ports;

import br.com.nora.api.domain.workflow.WorkflowSchedule;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the run state of a scheduled flow (V032, ADR 0047). Every method requires
 * tenantId (ADR 0002) — the scheduler thread carries no JWT, so the caller has already propagated
 * the tenant through {@link TenantRlsContext}.
 */
public interface WorkflowScheduleRepository {

    /**
     * Creates or refreshes the schedule of a workflow saved with the {@code schedule.cron} trigger.
     *
     * <p>An existing row KEEPS its running state — {@code nextFireAt}, {@code windowFrom} and the
     * claim — unless the compiled expression changed or the row is already overdue. Rewriting them
     * on every save would mean an edit to an action reset the window and dropped the meetings
     * analysed since the last run; not rewriting them at all would mean a flow paused for a month
     * fires immediately on reactivation, over a month of meetings.
     */
    void upsert(WorkflowSchedule schedule);

    /** Removes the schedule of a workflow whose trigger is no longer {@code schedule.cron}. */
    void deleteByWorkflowId(UUID workflowId, UUID tenantId);

    /**
     * The tenant's schedules that are due and claimable: {@code nextFireAt <= now}, the workflow is
     * ACTIVE, and no live claim is held. A claim older than {@code leaseExpiredBefore} counts as
     * abandoned — without that, a claim left behind by a dead JVM would freeze the schedule
     * forever. Ordered by {@code nextFireAt}, so the oldest overdue occurrence goes first.
     */
    List<WorkflowSchedule> findDue(
            UUID tenantId, OffsetDateTime now, OffsetDateTime leaseExpiredBefore);

    /**
     * Takes a due run, atomically. Returns true when THIS caller took it and false when it did not
     * — because another process got there first, or because a live claim means the previous run is
     * still executing and this occurrence is skipped rather than queued.
     *
     * <p>The statement is a COMPARE-AND-SWAP on {@code expectedNextFireAt}: it matches the value
     * the caller read a moment earlier, so of two processes reading the same due row exactly one
     * can win. It advances {@code nextFireAt} in the same statement, which is what collapses missed
     * occurrences into one, and deliberately does NOT touch {@code windowFrom} — see {@link
     * #release}.
     */
    boolean claim(
            UUID workflowId,
            UUID tenantId,
            OffsetDateTime expectedNextFireAt,
            OffsetDateTime firedAt,
            OffsetDateTime nextFireAt,
            OffsetDateTime leaseExpiredBefore,
            String owner);

    /**
     * Ends a run that COMPLETED: clears the claim and advances {@code windowFrom} to the instant it
     * fired, so the next run reads the period that starts here.
     *
     * <p>It must not be called when the run failed structurally. Leaving {@code windowFrom} where
     * it was is what makes the meetings of a dead run at-least-once, and leaving the claim in place
     * is what makes the abandonment visible until the lease expires.
     */
    void release(UUID workflowId, UUID tenantId, OffsetDateTime windowFrom, String owner);
}

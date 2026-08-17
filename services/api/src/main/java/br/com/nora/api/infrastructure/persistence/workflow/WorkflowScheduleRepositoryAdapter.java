package br.com.nora.api.infrastructure.persistence.workflow;

import br.com.nora.api.application.ports.WorkflowScheduleRepository;
import br.com.nora.api.domain.workflow.WorkflowSchedule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter (via {@link EntityManager} + native SQL) for the run state of a scheduled flow
 * (V032, ADR 0047). Same style as {@code WorkflowRepositoryAdapter}: always scoped by tenant_id
 * (RLS, ADR 0028).
 *
 * <p>The two writes that matter are {@link #claim} and {@link #release}, and what they touch is the
 * whole design: the claim advances {@code next_fire_at} so missed occurrences collapse into one,
 * and only the release advances {@code window_from} so a run that dies mid-flight does not take its
 * meetings with it.
 */
@Repository
public class WorkflowScheduleRepositoryAdapter implements WorkflowScheduleRepository {

    private static final String SELECT_COLUMNS =
            "SELECT s.workflow_id, s.tenant_id, s.cron, s.timezone, s.next_fire_at, s.window_from,"
                    + " s.last_fire_at, s.claimed_at, s.claim_owner FROM workflow_schedules s ";

    @PersistenceContext private EntityManager em;

    /**
     * The {@code CASE} on both timestamps is the whole point of this statement. A save that did not
     * change the schedule must not reset the running state — editing an action would otherwise move
     * {@code window_from} to now and drop the meetings analysed since the last run. A save that DID
     * change it, or that lands on a row already overdue (a flow that was paused for a month and
     * reactivated), has to restart from now, or the first fire after reactivation would sweep a
     * month of meetings at once.
     */
    @Override
    @Transactional
    public void upsert(WorkflowSchedule schedule) {
        em.createNativeQuery(
                        "INSERT INTO workflow_schedules (workflow_id, tenant_id, cron, timezone,"
                                + " next_fire_at, window_from, created_at, updated_at) VALUES"
                                + " (:workflowId, :tenantId, :cron, :timezone, :nextFireAt,"
                                + " :windowFrom, :updatedAt, :updatedAt) ON CONFLICT (workflow_id)"
                                + " DO UPDATE SET cron = EXCLUDED.cron, timezone ="
                                + " EXCLUDED.timezone, next_fire_at = CASE WHEN"
                                + " workflow_schedules.cron <> EXCLUDED.cron OR"
                                + " workflow_schedules.next_fire_at <= EXCLUDED.window_from THEN"
                                + " EXCLUDED.next_fire_at ELSE workflow_schedules.next_fire_at END,"
                                + " window_from = CASE WHEN workflow_schedules.cron <>"
                                + " EXCLUDED.cron OR workflow_schedules.next_fire_at <="
                                + " EXCLUDED.window_from THEN EXCLUDED.window_from ELSE"
                                + " workflow_schedules.window_from END, updated_at ="
                                + " EXCLUDED.updated_at")
                .setParameter("workflowId", schedule.workflowId())
                .setParameter("tenantId", schedule.tenantId())
                .setParameter("cron", schedule.cron())
                .setParameter("timezone", schedule.timezone())
                .setParameter("nextFireAt", toTimestamp(schedule.nextFireAt()))
                .setParameter("windowFrom", toTimestamp(schedule.windowFrom()))
                .setParameter("updatedAt", toTimestamp(schedule.windowFrom()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void deleteByWorkflowId(UUID workflowId, UUID tenantId) {
        em.createNativeQuery(
                        "DELETE FROM workflow_schedules WHERE workflow_id = :workflowId AND"
                                + " tenant_id = :tenantId")
                .setParameter("workflowId", workflowId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<WorkflowSchedule> findDue(
            UUID tenantId, OffsetDateTime now, OffsetDateTime leaseExpiredBefore) {
        // The join on workflows is what keeps a deactivated flow from being due every minute: its
        // schedule row survives the pause, and WorkflowService restarts it on reactivation.
        var query =
                em.createNativeQuery(
                        SELECT_COLUMNS
                                + "JOIN workflows w ON w.id = s.workflow_id AND w.tenant_id ="
                                + " s.tenant_id WHERE s.tenant_id = :tenantId AND w.active AND"
                                + " s.next_fire_at <= :now AND (s.claimed_at IS NULL OR"
                                + " s.claimed_at < :leaseExpiredBefore) ORDER BY s.next_fire_at"
                                + " ASC");
        query.setParameter("tenantId", tenantId);
        query.setParameter("now", toTimestamp(now));
        query.setParameter("leaseExpiredBefore", toTimestamp(leaseExpiredBefore));
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<WorkflowSchedule> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toSchedule(r));
        }
        return result;
    }

    /**
     * Compare-and-swap: {@code next_fire_at = :expectedNextFireAt} matches the value the caller
     * read a moment earlier, so two processes reading the same due row produce one winner and one
     * no-match. The claim guard beside it is the overlap rule — a live claim means the previous run
     * has not finished, and this occurrence is skipped.
     */
    @Override
    @Transactional
    public boolean claim(
            UUID workflowId,
            UUID tenantId,
            OffsetDateTime expectedNextFireAt,
            OffsetDateTime firedAt,
            OffsetDateTime nextFireAt,
            OffsetDateTime leaseExpiredBefore,
            String owner) {
        int updated =
                em.createNativeQuery(
                                "UPDATE workflow_schedules SET claimed_at = :firedAt, claim_owner ="
                                        + " :owner, last_fire_at = :firedAt, next_fire_at ="
                                        + " :nextFireAt, updated_at = :firedAt WHERE workflow_id ="
                                        + " :workflowId AND tenant_id = :tenantId AND next_fire_at"
                                        + " = :expectedNextFireAt AND (claimed_at IS NULL OR"
                                        + " claimed_at < :leaseExpiredBefore)")
                        .setParameter("firedAt", toTimestamp(firedAt))
                        .setParameter("owner", owner)
                        .setParameter("nextFireAt", toTimestamp(nextFireAt))
                        .setParameter("workflowId", workflowId)
                        .setParameter("tenantId", tenantId)
                        .setParameter("expectedNextFireAt", toTimestamp(expectedNextFireAt))
                        .setParameter("leaseExpiredBefore", toTimestamp(leaseExpiredBefore))
                        .executeUpdate();
        return updated == 1;
    }

    @Override
    @Transactional
    public void release(UUID workflowId, UUID tenantId, OffsetDateTime windowFrom, String owner) {
        em.createNativeQuery(
                        "UPDATE workflow_schedules SET claimed_at = NULL, claim_owner = NULL,"
                                + " window_from = :windowFrom, updated_at = :windowFrom WHERE"
                                + " workflow_id = :workflowId AND tenant_id = :tenantId AND"
                                + " claim_owner = :owner")
                .setParameter("windowFrom", toTimestamp(windowFrom))
                .setParameter("workflowId", workflowId)
                .setParameter("tenantId", tenantId)
                .setParameter("owner", owner)
                .executeUpdate();
    }

    private WorkflowSchedule toSchedule(Object[] r) {
        return new WorkflowSchedule(
                (UUID) r[0],
                (UUID) r[1],
                (String) r[2],
                (String) r[3],
                toOffset(r[4]),
                toOffset(r[5]),
                toOffset(r[6]),
                toOffset(r[7]),
                (String) r[8]);
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof java.time.Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }
}

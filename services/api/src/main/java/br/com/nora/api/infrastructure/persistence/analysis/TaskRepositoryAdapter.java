package br.com.nora.api.infrastructure.persistence.analysis;

import br.com.nora.api.application.ports.TaskRepository;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Priority;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter for the meeting_action_items table seen as a "tenant task" (US22-US24). Uses native SQL
 * to project a flattened row (with meeting_id and meeting title) without having to load the whole
 * MeetingAnalysis aggregate.
 */
@Repository
public class TaskRepositoryAdapter implements TaskRepository {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TaskRow> listByTenant(UUID tenantId, ActionItemStatus statusFilter) {
        String sql =
                "SELECT ai.id, ai.title, ai.assignee, ai.due_date, ai.priority, ai.status, "
                        + "       a.meeting_id, m.title AS meeting_title, ai.updated_at "
                        + "FROM meeting_action_items ai "
                        + "JOIN meeting_analyses a ON a.id = ai.analysis_id "
                        + "JOIN meetings m ON m.id = a.meeting_id "
                        + "WHERE ai.tenant_id = :tenantId "
                        + "  AND (CAST(:status AS text) IS NULL OR ai.status = CAST(:status AS text)) "
                        + "ORDER BY CASE ai.status WHEN 'OPEN' THEN 0 WHEN 'IN_PROGRESS' THEN 1 ELSE 2 END, "
                        + "         ai.updated_at DESC";
        var query = em.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("status", statusFilter == null ? null : statusFilter.name());
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<TaskRow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toRow(r));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<TaskRow> findByIdAndTenant(UUID id, UUID tenantId) {
        String sql =
                "SELECT ai.id, ai.title, ai.assignee, ai.due_date, ai.priority, ai.status, "
                        + "       a.meeting_id, m.title AS meeting_title, ai.updated_at "
                        + "FROM meeting_action_items ai "
                        + "JOIN meeting_analyses a ON a.id = ai.analysis_id "
                        + "JOIN meetings m ON m.id = a.meeting_id "
                        + "WHERE ai.id = :id AND ai.tenant_id = :tenantId";
        var query = em.createNativeQuery(sql);
        query.setParameter("id", id);
        query.setParameter("tenantId", tenantId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toRow(rows.get(0)));
    }

    @Override
    @Transactional
    public void updateStatus(UUID id, UUID tenantId, ActionItemStatus newStatus) {
        // `completed_at` (V030) is maintained here because this is the only writer of `status` in
        // the codebase. Entering DONE stamps it, leaving DONE clears it — so an item reopened
        // after being finished stops counting as a completion rather than counting twice — and
        // DONE re-applied to a DONE item keeps the original instant instead of moving it forward.
        // One statement, so the two columns cannot be left disagreeing.
        em.createNativeQuery(
                        "UPDATE meeting_action_items SET status = :status, updated_at = NOW(), "
                                + "completed_at = CASE WHEN CAST(:status AS text) = 'DONE' "
                                + "THEN COALESCE(completed_at, NOW()) ELSE NULL END "
                                + "WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("status", newStatus.name())
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updateTitle(UUID id, UUID tenantId, String newTitle) {
        em.createNativeQuery(
                        "UPDATE meeting_action_items SET title = :title, updated_at = NOW() "
                                + "WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("title", newTitle)
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updateDueDate(UUID id, UUID tenantId, LocalDate newDueDate) {
        // The value is bound as text and CAST in SQL, the same idiom the listing already uses for
        // its nullable status filter: a native query cannot infer the type of a plain null bind,
        // and clearing the date is exactly the case that binds null.
        em.createNativeQuery(
                        "UPDATE meeting_action_items SET due_date = CAST(:dueDate AS date), "
                                + "updated_at = NOW() WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("dueDate", newDueDate == null ? null : newDueDate.toString())
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    private TaskRow toRow(Object[] r) {
        UUID id = (UUID) r[0];
        String title = (String) r[1];
        String assignee = (String) r[2];
        LocalDate dueDate = r[3] == null ? null : ((Date) r[3]).toLocalDate();
        Priority priority = Priority.valueOf((String) r[4]);
        ActionItemStatus status = ActionItemStatus.valueOf((String) r[5]);
        UUID meetingId = (UUID) r[6];
        String meetingTitle = (String) r[7];
        OffsetDateTime updatedAt = toOffset(r[8]);
        return new TaskRow(
                id, title, assignee, dueDate, priority, status, meetingId, meetingTitle, updatedAt);
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
}

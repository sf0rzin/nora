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
 * Adapter para a tabela meeting_action_items vista como "tarefa do tenant" (US22-US24). Usa SQL
 * nativo para projetar uma linha achatada (com meeting_id e meeting title) sem precisar carregar a
 * agregada MeetingAnalysis inteira.
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
        em.createNativeQuery(
                        "UPDATE meeting_action_items SET status = :status, updated_at = NOW() "
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

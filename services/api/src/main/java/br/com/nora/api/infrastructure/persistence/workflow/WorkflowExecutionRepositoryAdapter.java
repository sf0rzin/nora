package br.com.nora.api.infrastructure.persistence.workflow;

import br.com.nora.api.application.ports.WorkflowExecutionRepository;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowExecutionStatus;
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
 * Adapter JDBC do histórico de execuções de workflows (V023). Mesmo estilo do {@code
 * WorkflowRepositoryAdapter}; escrita em duas fases (create RUNNING → finish terminal) para que uma
 * execução em andamento já apareça no histórico.
 */
@Repository
public class WorkflowExecutionRepositoryAdapter implements WorkflowExecutionRepository {

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional
    public void create(WorkflowExecution execution) {
        em.createNativeQuery(
                        "INSERT INTO workflow_executions (id, workflow_id, tenant_id, event_type,"
                                + " status, log_json, created_at, finished_at) VALUES (:id,"
                                + " :workflowId, :tenantId, :eventType, :status, CAST(:logJson AS"
                                + " jsonb), :createdAt, :finishedAt)")
                .setParameter("id", execution.id())
                .setParameter("workflowId", execution.workflowId())
                .setParameter("tenantId", execution.tenantId())
                .setParameter("eventType", execution.eventType())
                .setParameter("status", execution.status().name())
                .setParameter("logJson", execution.logJson() == null ? "[]" : execution.logJson())
                .setParameter("createdAt", toTimestamp(execution.createdAt()))
                .setParameter("finishedAt", toTimestamp(execution.finishedAt()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void finish(
            UUID id,
            UUID tenantId,
            WorkflowExecutionStatus status,
            String logJson,
            OffsetDateTime finishedAt) {
        em.createNativeQuery(
                        "UPDATE workflow_executions SET status = :status, log_json = CAST(:logJson"
                                + " AS jsonb), finished_at = :finishedAt WHERE id = :id AND"
                                + " tenant_id = :tenantId")
                .setParameter("status", status.name())
                .setParameter("logJson", logJson == null ? "[]" : logJson)
                .setParameter("finishedAt", toTimestamp(finishedAt))
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<WorkflowExecution> listByWorkflow(UUID workflowId, UUID tenantId, int limit) {
        var query =
                em.createNativeQuery(
                        "SELECT e.id, e.workflow_id, e.tenant_id, e.event_type, e.status,"
                                + " e.log_json::text, e.created_at, e.finished_at FROM"
                                + " workflow_executions e WHERE e.workflow_id = :workflowId AND"
                                + " e.tenant_id = :tenantId ORDER BY e.created_at DESC");
        query.setParameter("workflowId", workflowId);
        query.setParameter("tenantId", tenantId);
        query.setMaxResults(Math.max(1, limit));
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<WorkflowExecution> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toExecution(r));
        }
        return result;
    }

    private WorkflowExecution toExecution(Object[] r) {
        return new WorkflowExecution(
                (UUID) r[0],
                (UUID) r[1],
                (UUID) r[2],
                (String) r[3],
                WorkflowExecutionStatus.valueOf((String) r[4]),
                (String) r[5],
                toOffset(r[6]),
                toOffset(r[7]));
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

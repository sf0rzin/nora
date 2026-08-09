package br.com.nora.api.application.ports;

import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowExecutionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for the workflow execution history. Every query requires tenantId (ADR 0002).
 */
public interface WorkflowExecutionRepository {

    void create(WorkflowExecution execution);

    /** Finishes an execution: terminal status (SUCCESS/FAILED) + full log + finished_at. */
    void finish(
            UUID id,
            UUID tenantId,
            WorkflowExecutionStatus status,
            String logJson,
            OffsetDateTime finishedAt);

    /** History of a workflow, most recent first, limited to {@code limit} rows. */
    List<WorkflowExecution> listByWorkflow(UUID workflowId, UUID tenantId, int limit);
}

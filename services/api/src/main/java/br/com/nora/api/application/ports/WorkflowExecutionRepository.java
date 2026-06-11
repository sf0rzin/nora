package br.com.nora.api.application.ports;

import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.domain.workflow.WorkflowExecutionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Porta de persistência do histórico de execuções de workflows. Toda consulta exige tenantId (ADR
 * 0002).
 */
public interface WorkflowExecutionRepository {

    void create(WorkflowExecution execution);

    /** Finaliza uma execução: status terminal (SUCCESS/FAILED) + log completo + finished_at. */
    void finish(
            UUID id,
            UUID tenantId,
            WorkflowExecutionStatus status,
            String logJson,
            OffsetDateTime finishedAt);

    /** Histórico de um workflow, mais recente primeiro, limitado a {@code limit} linhas. */
    List<WorkflowExecution> listByWorkflow(UUID workflowId, UUID tenantId, int limit);
}

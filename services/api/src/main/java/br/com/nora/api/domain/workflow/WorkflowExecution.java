package br.com.nora.api.domain.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma execução (disparo) de um workflow — real (evento de domínio) ou teste manual. O log passo a
 * passo vive em {@code logJson} (array de {at, nodeId, level, message}) e é o que a UI de histórico
 * mostra. Imutável.
 */
public record WorkflowExecution(
        UUID id,
        UUID workflowId,
        UUID tenantId,
        String eventType,
        WorkflowExecutionStatus status,
        String logJson,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    public WorkflowExecution {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
    }
}

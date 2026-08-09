package br.com.nora.api.domain.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An execution (trigger) of a workflow — real (domain event) or manual test. The step-by-step log
 * lives in {@code logJson} (array of {at, nodeId, level, message}) and is what the history UI
 * shows. Immutable.
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

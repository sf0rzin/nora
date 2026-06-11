package br.com.nora.api.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma execução do histórico de um workflow. {@code log} é o array de passos ({at, nodeId, level,
 * message}) que a UI renderiza linha a linha.
 */
public record WorkflowExecutionResponse(
        UUID id,
        UUID workflowId,
        String eventType,
        String status,
        JsonNode log,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {}

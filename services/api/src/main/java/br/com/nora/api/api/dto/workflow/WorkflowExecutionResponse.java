package br.com.nora.api.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One execution from a workflow's history. {@code log} is the array of steps ({at, nodeId, level,
 * message}) that the UI renders line by line.
 */
public record WorkflowExecutionResponse(
        UUID id,
        UUID workflowId,
        String eventType,
        String status,
        JsonNode log,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {}

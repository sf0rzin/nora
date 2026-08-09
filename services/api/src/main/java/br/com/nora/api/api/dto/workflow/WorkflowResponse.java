package br.com.nora.api.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Full workflow (with the canvas graph in {@code definition}). */
public record WorkflowResponse(
        UUID id,
        String name,
        String triggerType,
        boolean active,
        JsonNode definition,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

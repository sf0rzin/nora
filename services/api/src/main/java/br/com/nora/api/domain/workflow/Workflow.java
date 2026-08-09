package br.com.nora.api.domain.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * NORA Flows automation. Belongs to a tenant (ADR 0002). The graph drawn on the canvas
 * (trigger/condition/action nodes + edges) lives serialized in {@code definitionJson}; {@code
 * triggerType} is derived from the trigger node and denormalized for the engine's fast match.
 * Immutable.
 */
public record Workflow(
        UUID id,
        UUID tenantId,
        String name,
        TriggerType triggerType,
        String definitionJson,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public Workflow {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (triggerType == null) {
            throw new IllegalArgumentException("triggerType is required");
        }
        if (definitionJson == null || definitionJson.isBlank()) {
            throw new IllegalArgumentException("definitionJson is required");
        }
    }
}

package br.com.nora.api.domain.workflow;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Automação do NORA Flows. Pertence a um tenant (ADR 0002). O grafo desenhado no canvas (nós de
 * gatilho/condição/ação + arestas) vive serializado em {@code definitionJson}; {@code triggerType}
 * é derivado do nó de gatilho e denormalizado para o match rápido do engine. Imutável.
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

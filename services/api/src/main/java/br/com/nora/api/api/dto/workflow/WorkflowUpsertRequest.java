package br.com.nora.api.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Payload de criação/edição de um workflow. O gatilho NÃO vem separado: é derivado do nó de gatilho
 * dentro da {@code definition} (grafo do canvas — uma fonte de verdade só). {@code active} nulo =
 * true (criar já ativo).
 */
public record WorkflowUpsertRequest(
        @NotBlank @Size(max = 120) String name, Boolean active, @NotNull JsonNode definition) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}

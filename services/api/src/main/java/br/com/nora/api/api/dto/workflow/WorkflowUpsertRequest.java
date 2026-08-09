package br.com.nora.api.api.dto.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create/edit payload for a workflow. The trigger does NOT come separately: it is derived from the
 * trigger node inside the {@code definition} (canvas graph — a single source of truth). {@code
 * active} null = true (create already active).
 */
public record WorkflowUpsertRequest(
        @NotBlank @Size(max = 120) String name, Boolean active, @NotNull JsonNode definition) {

    public boolean activeOrDefault() {
        return active == null || active;
    }
}

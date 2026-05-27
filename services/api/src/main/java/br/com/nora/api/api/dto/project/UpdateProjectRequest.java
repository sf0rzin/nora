package br.com.nora.api.api.dto.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import jakarta.validation.constraints.Size;

/**
 * Body de PATCH /projects/{id}. Todos os campos opcionais — envie apenas o que quer mudar. {@code
 * status} aceita ACTIVE / ARCHIVED (case-insensitive).
 */
@JsonInclude(Include.NON_NULL)
public record UpdateProjectRequest(
        @Size(min = 1, max = 200, message = "name length must be between 1 and 200") String name,
        @Size(max = 5000, message = "description must be at most 5000 chars") String description,
        String status) {}

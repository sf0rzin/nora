package br.com.nora.api.api.dto.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body de POST /projects. Validacoes refletem os limites do dominio Project. */
public record CreateProjectRequest(
        @NotBlank(message = "name is required")
                @Size(min = 1, max = 200, message = "name length must be between 1 and 200")
                String name,
        @Size(max = 5000, message = "description must be at most 5000 chars") String description) {}

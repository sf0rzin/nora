package br.com.nora.api.api.dto.project;

import br.com.nora.api.domain.project.Project;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Representacao de um project nas respostas da API. */
@JsonInclude(Include.NON_NULL)
public record ProjectResponse(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.id(),
                p.tenantId(),
                p.name(),
                p.description(),
                p.status().name(),
                p.createdAt(),
                p.updatedAt());
    }
}

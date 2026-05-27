package br.com.nora.api.domain.project;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Projeto (agrupador de reunioes) de um tenant. Imutavel.
 *
 * <p>Um usuario Core agrupa suas meetings em projects (relacao 1:N opcional). {@code description} e
 * opcional. {@code status} controla ACTIVE/ARCHIVED. Soft-delete via {@code deletedAt} (V013/V019).
 */
public record Project(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        ProjectStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime deletedAt) {

    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 200;

    public Project {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() < NAME_MIN || trimmedName.length() > NAME_MAX) {
            throw new IllegalArgumentException(
                    "name length must be between " + NAME_MIN + " and " + NAME_MAX);
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        String trimmedDescription = description == null ? null : description.trim();
        if (trimmedDescription != null && trimmedDescription.isEmpty()) {
            trimmedDescription = null;
        }
        OffsetDateTime resolvedCreatedAt = createdAt == null ? OffsetDateTime.now() : createdAt;
        name = trimmedName;
        description = trimmedDescription;
        createdAt = resolvedCreatedAt;
        updatedAt = updatedAt == null ? resolvedCreatedAt : updatedAt;
    }

    /** Factory para criar um projeto novo (id gerado, status ACTIVE, timestamps = now). */
    public static Project create(UUID tenantId, String name, String description) {
        OffsetDateTime now = OffsetDateTime.now();
        return new Project(
                UUID.randomUUID(),
                tenantId,
                name,
                description,
                ProjectStatus.ACTIVE,
                now,
                now,
                null);
    }

    /** Copia com novo nome/descricao (updatedAt = now). Null em description limpa o campo. */
    public Project withDetails(String newName, String newDescription) {
        return new Project(
                id,
                tenantId,
                newName,
                newDescription,
                status,
                createdAt,
                OffsetDateTime.now(),
                deletedAt);
    }

    /** Copia com novo status (updatedAt = now). */
    public Project withStatus(ProjectStatus newStatus) {
        if (newStatus == null) {
            throw new IllegalArgumentException("status is required");
        }
        return new Project(
                id,
                tenantId,
                name,
                description,
                newStatus,
                createdAt,
                OffsetDateTime.now(),
                deletedAt);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}

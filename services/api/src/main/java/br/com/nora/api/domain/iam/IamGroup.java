package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Grupo IAM (coleção de usuarios) escopado por tenant. */
public record IamGroup(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/** IAM group (collection of users) scoped by tenant. */
public record IamGroup(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

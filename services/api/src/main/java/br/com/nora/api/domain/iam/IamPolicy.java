package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Policy IAM persistida (versao corrente). */
public record IamPolicy(
        UUID id,
        UUID tenantId,
        String name,
        String description,
        PolicyDocument document,
        int currentVersion,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

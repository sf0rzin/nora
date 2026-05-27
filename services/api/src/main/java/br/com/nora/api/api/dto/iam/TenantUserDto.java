package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Usuario do tenant na listagem IAM (sem dados sensiveis). */
public record TenantUserDto(
        UUID id,
        String email,
        String displayName,
        boolean isRoot,
        String status,
        OffsetDateTime createdAt) {}

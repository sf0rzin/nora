package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GroupDto(
        UUID id,
        String name,
        String description,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PolicyDto(
        UUID id,
        String name,
        String description,
        JsonNode document,
        int currentVersion,
        UUID createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}

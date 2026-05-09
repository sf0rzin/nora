package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AuditEventDto(
        UUID id,
        UUID actorUserId,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> payload,
        OffsetDateTime createdAt) {}

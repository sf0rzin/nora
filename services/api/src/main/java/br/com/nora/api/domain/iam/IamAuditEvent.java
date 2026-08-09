package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Audit event for IAM changes (US40). */
public record IamAuditEvent(
        UUID id,
        UUID tenantId,
        UUID actorUserId,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> payload,
        OffsetDateTime createdAt) {}

package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Creation response and listing items for invites (US06). Schema in {@code
 * docs/api/examples/iam-invite-response.json}. The {@code token} field is NEVER returned.
 */
public record InviteResponse(
        UUID id,
        UUID tenantId,
        String email,
        String status,
        UUID invitedBy,
        OffsetDateTime invitedAt,
        OffsetDateTime expiresAt,
        List<UUID> groupIds,
        OffsetDateTime acceptedAt,
        UUID acceptedUserId) {}

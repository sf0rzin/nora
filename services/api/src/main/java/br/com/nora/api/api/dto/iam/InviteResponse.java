package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resposta de criacao e itens da listagem de convites (US06). Schema em {@code
 * docs/api/examples/iam-invite-response.json}. O campo {@code token} NUNCA e devolvido.
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

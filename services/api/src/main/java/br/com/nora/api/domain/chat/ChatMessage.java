package br.com.nora.api.domain.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A message inside a chat session. Belongs to a {@link ChatSession} (via sessionId) and carries an
 * explicit tenant_id (ADR 0002). Immutable.
 */
public record ChatMessage(
        UUID id,
        UUID sessionId,
        UUID tenantId,
        ChatRole role,
        String content,
        OffsetDateTime createdAt) {}

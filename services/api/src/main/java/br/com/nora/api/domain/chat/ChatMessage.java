package br.com.nora.api.domain.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Uma mensagem dentro de uma sessão de chat. Pertence a uma {@link ChatSession} (via sessionId) e
 * carrega tenant_id explícito (ADR 0002). Imutável.
 */
public record ChatMessage(
        UUID id,
        UUID sessionId,
        UUID tenantId,
        ChatRole role,
        String content,
        OffsetDateTime createdAt) {}

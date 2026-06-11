package br.com.nora.api.api.dto.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Detalhe da sessão de chat com o histórico de mensagens em ordem cronológica. */
public record ChatSessionDetailResponse(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ChatMessageResponse> messages) {}

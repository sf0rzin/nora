package br.com.nora.api.api.dto.chat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Chat session detail with the message history in chronological order. */
public record ChatSessionDetailResponse(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<ChatMessageResponse> messages) {}

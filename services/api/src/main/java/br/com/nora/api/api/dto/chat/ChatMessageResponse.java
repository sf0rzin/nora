package br.com.nora.api.api.dto.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One chat message in the response (role in wire format: {@code "user"}/{@code "assistant"}). */
public record ChatMessageResponse(UUID id, String role, String content, OffsetDateTime createdAt) {}

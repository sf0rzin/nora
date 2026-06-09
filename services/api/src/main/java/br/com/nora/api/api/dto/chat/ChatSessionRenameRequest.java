package br.com.nora.api.api.dto.chat;

import jakarta.validation.constraints.NotBlank;

/** Body de PATCH /chat/sessions/{id}. */
public record ChatSessionRenameRequest(@NotBlank String title) {}

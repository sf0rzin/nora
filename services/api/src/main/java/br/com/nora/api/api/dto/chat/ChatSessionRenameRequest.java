package br.com.nora.api.api.dto.chat;

import jakarta.validation.constraints.NotBlank;

/** Body of PATCH /chat/sessions/{id}. */
public record ChatSessionRenameRequest(@NotBlank String title) {}

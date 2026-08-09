package br.com.nora.api.api.dto.chat;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /chat/sessions/{id}/messages. {@code role} = {@code "user"}|{@code "assistant"}. */
public record ChatMessageCreateRequest(@NotBlank String role, @NotBlank String content) {}

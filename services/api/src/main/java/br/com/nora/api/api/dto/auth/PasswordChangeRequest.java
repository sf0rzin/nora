package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Troca de senha autenticada (aba Seguranca). A policy da nova senha valida na aplicacao. */
public record PasswordChangeRequest(
        @NotBlank String currentPassword, @NotBlank String newPassword) {}

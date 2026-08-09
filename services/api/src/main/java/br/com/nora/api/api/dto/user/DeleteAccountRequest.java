package br.com.nora.api.api.dto.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Permanent account deletion (LGPD, danger zone). Requires the current password as strong
 * confirmation — the UI's typed e-mail confirm is just UX, the password is the real barrier.
 */
public record DeleteAccountRequest(@NotBlank String password) {}

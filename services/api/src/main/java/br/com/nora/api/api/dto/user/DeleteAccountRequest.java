package br.com.nora.api.api.dto.user;

import jakarta.validation.constraints.NotBlank;

/**
 * Exclusao definitiva da conta (LGPD, zona de perigo). Exige a senha atual como confirmacao forte —
 * o typed-confirm de e-mail da UI e so UX, a senha e a barreira real.
 */
public record DeleteAccountRequest(@NotBlank String password) {}

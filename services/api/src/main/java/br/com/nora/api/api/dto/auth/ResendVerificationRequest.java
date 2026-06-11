package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Reenvio do e-mail de verificacao (publico; resposta indistinguivel anti-enumeracao). */
public record ResendVerificationRequest(@NotBlank String email) {}

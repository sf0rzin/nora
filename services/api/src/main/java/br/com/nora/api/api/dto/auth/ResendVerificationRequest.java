package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/** Verification e-mail resend (public; indistinguishable response, anti-enumeration). */
public record ResendVerificationRequest(@NotBlank String email) {}

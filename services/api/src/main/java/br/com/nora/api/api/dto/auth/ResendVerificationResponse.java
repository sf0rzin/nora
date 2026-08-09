package br.com.nora.api.api.dto.auth;

/**
 * Verification resend response. {@code verificationDevToken} is only populated with
 * EXPOSE_DEV_TOKENS=true (dev/CI) — in production it is always null.
 */
public record ResendVerificationResponse(String message, String verificationDevToken) {}

package br.com.nora.api.api.dto.auth;

/**
 * Resposta do reenvio de verificacao. {@code verificationDevToken} so vem preenchido com
 * EXPOSE_DEV_TOKENS=true (dev/CI) — em producao e sempre null.
 */
public record ResendVerificationResponse(String message, String verificationDevToken) {}

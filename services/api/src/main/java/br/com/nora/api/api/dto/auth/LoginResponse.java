package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Login / accept-invite response.
 *
 * <p>{@code accessToken}/{@code refreshToken} only appear in the body for NATIVE clients (Tauri
 * desktop, which stores the tokens in the OS keyring). The web client sends the {@code
 * X-NORA-Client: web} header and receives the session only via httpOnly cookies — the token fields
 * come back null and are omitted from the JSON, so that an XSS cannot read the 30-day refresh (ADR
 * 0020/0012).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        UUID tenantId,
        String email,
        String displayName) {}

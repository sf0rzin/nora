package br.com.nora.api.api.dto.auth;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds,
        UUID userId,
        UUID tenantId,
        String email,
        String displayName) {}

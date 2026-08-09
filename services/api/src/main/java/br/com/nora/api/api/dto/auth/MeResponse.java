package br.com.nora.api.api.dto.auth;

import java.time.Instant;
import java.util.UUID;

/** Identity of the authenticated user (Account tab in settings). */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String displayName,
        boolean emailVerified,
        Instant createdAt) {}

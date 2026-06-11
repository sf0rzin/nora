package br.com.nora.api.api.dto.auth;

import java.time.Instant;
import java.util.UUID;

/** Identidade do usuario autenticado (aba Conta das configuracoes). */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String displayName,
        boolean emailVerified,
        Instant createdAt) {}

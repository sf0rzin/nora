package br.com.nora.api.api.dto.auth;

import java.util.UUID;

/**
 * Identidade do usuario autenticado (GET /auth/me). Fonte autoritativa pro front decidir o que
 * mostrar (ex.: nav admin so pra Root) sem confiar apenas no cookie de display setado no login.
 */
public record MeResponse(
        UUID userId,
        UUID tenantId,
        String email,
        String displayName,
        boolean isRoot,
        boolean emailVerified,
        String tenantName,
        String plan) {}

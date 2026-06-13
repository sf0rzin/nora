package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/**
 * Resposta de login / accept-invite.
 *
 * <p>{@code accessToken}/{@code refreshToken} só aparecem no body para clientes NATIVOS (desktop
 * Tauri, que guarda os tokens no keyring do OS). O cliente web envia o header {@code X-NORA-Client:
 * web} e recebe a sessão apenas via cookies httpOnly — os campos de token vêm null e são omitidos
 * do JSON, para que um XSS não consiga ler o refresh de 30 dias (ADR 0020/0012).
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

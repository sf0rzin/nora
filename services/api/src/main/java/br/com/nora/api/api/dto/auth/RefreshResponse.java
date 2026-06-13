package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resposta de {@code POST /auth/refresh}.
 *
 * <p>Os tokens saem nos cookies httpOnly {@code nora_access} / {@code nora_refresh} (clientes web)
 * e, APENAS para clientes nativos que autenticam o refresh via {@code Authorization: Bearer}
 * (desktop Tauri, keyring do OS), também no body. Quando o refresh chega pelo cookie (browser), os
 * campos de token vêm null e são omitidos do JSON — assim um XSS não consegue ler o refresh de 30
 * dias (ADR 0020). O web lê apenas {@code expiresInSeconds} desta resposta.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefreshResponse(
        String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

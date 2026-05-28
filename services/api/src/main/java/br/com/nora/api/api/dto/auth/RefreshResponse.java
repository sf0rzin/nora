package br.com.nora.api.api.dto.auth;

/**
 * Resposta de {@code POST /auth/refresh}.
 *
 * <p>Os tokens saem tanto nos cookies httpOnly {@code nora_access} / {@code nora_refresh} (pros
 * clientes web que dependem do browser pra session storage) quanto no body JSON (pros clientes
 * nativos como o desktop Tauri, que armazena tokens no keyring do OS via {@code SecretStore}).
 * Espelha o formato do {@link LoginResponse} pra simplificar a integração — quem prefere usar
 * cookies pode simplesmente ignorar os campos de token.
 */
public record RefreshResponse(
        String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

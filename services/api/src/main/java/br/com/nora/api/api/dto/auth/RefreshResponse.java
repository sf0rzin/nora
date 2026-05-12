package br.com.nora.api.api.dto.auth;

/**
 * Resposta de {@code POST /auth/refresh}. O novo access token sai no cookie httpOnly {@code
 * nora_access}; o JSON traz somente os metadados necessarios para o cliente agendar o proximo
 * refresh proativo.
 */
public record RefreshResponse(String tokenType, long expiresInSeconds) {}

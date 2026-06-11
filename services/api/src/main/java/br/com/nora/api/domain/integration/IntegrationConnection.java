package br.com.nora.api.domain.integration;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conexão OAuth de um tenant com um provedor externo (Google, Slack). Tokens trafegam em claro no
 * domínio/aplicação e são cifrados em repouso pelo adapter de persistência. {@code externalAccount}
 * identifica a conta conectada (ex.: e-mail Google) para a UI do hub. Imutável.
 */
public record IntegrationConnection(
        UUID id,
        UUID tenantId,
        UUID connectedByUserId,
        IntegrationProvider provider,
        String scopes,
        String externalAccount,
        String accessToken,
        String refreshToken,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public IntegrationConnection {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (connectedByUserId == null) {
            throw new IllegalArgumentException("connectedByUserId is required");
        }
        if (provider == null) {
            throw new IllegalArgumentException("provider is required");
        }
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
    }

    /** Cópia com tokens novos (refresh rotation) e updated_at bumpado. */
    public IntegrationConnection withTokens(
            String newAccessToken,
            String newRefreshToken,
            OffsetDateTime newExpiresAt,
            OffsetDateTime now) {
        return new IntegrationConnection(
                id,
                tenantId,
                connectedByUserId,
                provider,
                scopes,
                externalAccount,
                newAccessToken,
                newRefreshToken == null || newRefreshToken.isBlank()
                        ? refreshToken
                        : newRefreshToken,
                newExpiresAt,
                createdAt,
                now);
    }
}

package br.com.nora.api.domain.integration;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * OAuth connection of a tenant with an external provider (Google, Slack). Tokens travel in clear in
 * the domain/application and are encrypted at rest by the persistence adapter. {@code
 * externalAccount} identifies the connected account (e.g. Google e-mail) for the hub UI. Immutable.
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

    /** Copy with new tokens (refresh rotation) and updated_at bumped. */
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

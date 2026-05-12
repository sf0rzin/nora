package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token stateful (Round 2 / Subfase 1.3 A).
 *
 * <p>Token cru existe apenas no cookie httpOnly do navegador e em memoria efemera durante o login.
 * Persistencia armazena somente o SHA-256 hex ({@code tokenHash}). Vazamento do banco nao permite
 * reuso direto dos tokens.
 *
 * <p>Estado:
 *
 * <ul>
 *   <li>{@code revokedAt} != null -> revogado (logout ou rotacao futura) e nunca mais valido.
 *   <li>{@code expiresAt} no passado -> expirado pelo TTL longo (30 dias por padrao).
 *   <li>{@code lastUsedAt} marca o ultimo refresh; util pra observabilidade e revogar sessoes
 *       ociosas no futuro.
 * </ul>
 *
 * <p>Diferentemente de {@link OneTimeToken}, refresh nao e "consumido": pode ser usado varias vezes
 * dentro do TTL ate ser explicitamente revogado.
 */
public final class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant revokedAt;
    private final Instant createdAt;
    private Instant lastUsedAt;

    public RefreshToken(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant lastUsedAt) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastUsedAt = lastUsedAt;
    }

    /** Cria um refresh novinho com {@code revokedAt} e {@code lastUsedAt} nulos. */
    public static RefreshToken issue(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        return new RefreshToken(id, userId, tenantId, tokenHash, expiresAt, null, createdAt, null);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    /** Idempotente: revogar duas vezes mantem o primeiro timestamp. */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now);
        }
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = Objects.requireNonNull(now);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }
}

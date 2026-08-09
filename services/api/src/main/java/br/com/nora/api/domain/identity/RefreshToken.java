package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stateful refresh token with rotation + reuse detection (audit follow-up #3).
 *
 * <p>The raw token exists only in the browser's httpOnly cookie and in ephemeral memory during
 * login. Persistence stores only the SHA-256 hex ({@code tokenHash}). A database leak does not
 * allow direct reuse of the tokens.
 *
 * <p>State:
 *
 * <ul>
 *   <li>{@code revokedAt} != null -> revoked and never valid again.
 *   <li>{@code expiresAt} in the past -> expired by the long TTL (30 days by default).
 *   <li>{@code lastUsedAt} marks the last refresh.
 *   <li>{@code familyId} groups tokens of the same rotation chain. On /auth/refresh, we generate a
 *       child with the same family and revoke the parent. If a revoked token is presented again, we
 *       revoke the whole family (reuse detection).
 *   <li>{@code replacedById} points to the successor when the token is rotated (audit).
 * </ul>
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
    private final UUID familyId;
    private UUID replacedById;

    public RefreshToken(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt,
            Instant lastUsedAt,
            UUID familyId,
            UUID replacedById) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.revokedAt = revokedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastUsedAt = lastUsedAt;
        this.familyId = Objects.requireNonNull(familyId);
        this.replacedById = replacedById;
    }

    /** Creates a root refresh: {@code familyId} = {@code id} (each login starts a new chain). */
    public static RefreshToken issueRoot(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        return new RefreshToken(
                id, userId, tenantId, tokenHash, expiresAt, null, createdAt, null, id, null);
    }

    /**
     * Creates a child in the same chain: {@code familyId} inherited, the {@code replacedById}
     * parent->child must be set externally after creation.
     */
    public static RefreshToken issueChild(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt,
            UUID familyId) {
        return new RefreshToken(
                id, userId, tenantId, tokenHash, expiresAt, null, createdAt, null, familyId, null);
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

    /** Idempotent: revoking twice keeps the first timestamp. */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = Objects.requireNonNull(now);
        }
    }

    public void markUsed(Instant now) {
        this.lastUsedAt = Objects.requireNonNull(now);
    }

    public void markReplacedBy(UUID childId) {
        this.replacedById = Objects.requireNonNull(childId);
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

    public UUID familyId() {
        return familyId;
    }

    public UUID replacedById() {
        return replacedById;
    }
}

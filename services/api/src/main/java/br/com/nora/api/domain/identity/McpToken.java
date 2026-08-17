package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Long-lived, tenant-scoped bearer credential for the inbound MCP adapter (US27, ADR 0041 §3).
 *
 * <p>The raw token exists once, in the response that mints it, and never again: persistence holds
 * only the SHA-256 hex ({@code tokenHash}), the same shape {@link RefreshToken} and the one-time
 * tokens of V003/V011/V018 already use. A database leak yields no usable credential.
 *
 * <p>Deliberately simpler than {@link RefreshToken}: no rotation family and no reuse detection.
 * Those defend a credential that is exchanged on every request from a browser; this one sits in an
 * MCP client's configuration file for weeks and is never exchanged. What it has instead is
 * revocation, which is the only kill switch its lifecycle actually needs.
 *
 * <p>State:
 *
 * <ul>
 *   <li>{@code revokedAt} != null -> revoked, and never valid again.
 *   <li>{@code expiresAt} == null -> no hard expiry; the token lives until it is revoked.
 *   <li>{@code lastUsedAt} records the most recent successful resolution, so an unused token can be
 *       recognised in the listing before it is revoked.
 * </ul>
 */
public final class McpToken {

    private final UUID id;
    private final UUID tenantId;
    private final UUID userId;
    private final String name;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private Instant revokedAt;
    private Instant lastUsedAt;

    public McpToken(
            UUID id,
            UUID tenantId,
            UUID userId,
            String name,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            Instant lastUsedAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.userId = Objects.requireNonNull(userId);
        this.name = Objects.requireNonNull(name);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.lastUsedAt = lastUsedAt;
    }

    /** Mints a fresh, active token. {@code expiresAt} may be null (no hard expiry). */
    public static McpToken issue(
            UUID id,
            UUID tenantId,
            UUID userId,
            String name,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt) {
        return new McpToken(
                id, tenantId, userId, name, tokenHash, createdAt, expiresAt, null, null);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** A null {@code expiresAt} never expires; revocation is the only way such a token dies. */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
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

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }
}

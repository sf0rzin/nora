package br.com.nora.api.infrastructure.persistence.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import java.util.UUID;

/** Base mapeada para email_verification_tokens e password_reset_tokens. */
@MappedSuperclass
public abstract class OneTimeTokenJpaEntity {

    @Id protected UUID id;

    @Column(name = "user_id", nullable = false)
    protected UUID userId;

    @Column(name = "tenant_id", nullable = false)
    protected UUID tenantId;

    @Column(name = "token_hash", nullable = false, unique = true)
    protected String tokenHash;

    @Column(name = "expires_at", nullable = false)
    protected Instant expiresAt;

    @Column(name = "consumed_at")
    protected Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;

    protected OneTimeTokenJpaEntity() {}

    protected OneTimeTokenJpaEntity(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.consumedAt = consumedAt;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }
}

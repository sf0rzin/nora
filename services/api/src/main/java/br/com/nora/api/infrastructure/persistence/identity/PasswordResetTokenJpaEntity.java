package br.com.nora.api.infrastructure.persistence.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetTokenJpaEntity extends OneTimeTokenJpaEntity {

    protected PasswordResetTokenJpaEntity() {}

    public PasswordResetTokenJpaEntity(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt) {
        super(id, userId, tenantId, tokenHash, expiresAt, consumedAt, createdAt);
    }
}

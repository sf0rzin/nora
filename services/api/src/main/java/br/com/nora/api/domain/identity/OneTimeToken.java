package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Token de uso unico para verificacao de e-mail (US02) ou reset de senha (US04).
 *
 * <p>O campo {@code tokenHash} e o SHA-256 do token cru. Apenas o e-mail enviado ao usuario contem
 * o token cru. Isso evita que um vazamento do banco permita reuso direto.
 */
public final class OneTimeToken {

    public enum Purpose {
        EMAIL_VERIFICATION,
        PASSWORD_RESET
    }

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final String tokenHash;
    private final Instant expiresAt;
    private Instant consumedAt;
    private final Instant createdAt;
    private final Purpose purpose;

    public OneTimeToken(
            UUID id,
            UUID userId,
            UUID tenantId,
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant createdAt,
            Purpose purpose) {
        this.id = Objects.requireNonNull(id);
        this.userId = Objects.requireNonNull(userId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.consumedAt = consumedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.purpose = Objects.requireNonNull(purpose);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isUsable(Instant now) {
        return !isConsumed() && !isExpired(now);
    }

    public void consume(Instant now) {
        if (isConsumed()) {
            throw new IllegalStateException("token already consumed");
        }
        this.consumedAt = now;
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

    public Instant consumedAt() {
        return consumedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Purpose purpose() {
        return purpose;
    }
}

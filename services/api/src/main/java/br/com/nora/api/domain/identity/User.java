package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Agregado de usuario. Pertence sempre a um tenant. O e-mail e unico dentro do tenant (V002).
 *
 * <p>Regras encapsuladas:
 *
 * <ul>
 *   <li>Login so e permitido se o e-mail estiver verificado e o usuario estiver ACTIVE.
 *   <li>Troca de senha invalida sessoes futuras (refletido pelo updatedAt; sessoes sao stateless
 *       JWT, expiram naturalmente).
 * </ul>
 */
public final class User {

    private final UUID id;
    private final UUID tenantId;
    private final Email email;
    private String passwordHash;
    private final String displayName;
    private UserStatus status;
    private Instant emailVerifiedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    public User(
            UUID id,
            UUID tenantId,
            Email email,
            String passwordHash,
            String displayName,
            UserStatus status,
            Instant emailVerifiedAt,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.email = Objects.requireNonNull(email);
        this.passwordHash = Objects.requireNonNull(passwordHash);
        this.displayName = Objects.requireNonNull(displayName);
        this.status = Objects.requireNonNull(status);
        this.emailVerifiedAt = emailVerifiedAt;
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /** Construtor de novo usuario nao verificado, recem-criado em signup. */
    public static User newUnverified(
            UUID id,
            UUID tenantId,
            Email email,
            String passwordHash,
            String displayName,
            Instant now) {
        return new User(
                id, tenantId, email, passwordHash, displayName, UserStatus.ACTIVE, null, now, now);
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public boolean canLogin() {
        return status == UserStatus.ACTIVE && isEmailVerified();
    }

    public void markEmailVerified(Instant now) {
        if (this.emailVerifiedAt == null) {
            this.emailVerifiedAt = now;
            this.updatedAt = now;
        }
    }

    public void changePasswordHash(String newHash, Instant now) {
        this.passwordHash = Objects.requireNonNull(newHash);
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Email email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public UserStatus status() {
        return status;
    }

    public Instant emailVerifiedAt() {
        return emailVerifiedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}

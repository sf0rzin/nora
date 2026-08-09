package br.com.nora.api.domain.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * User aggregate. Always belongs to a tenant. The e-mail is unique within the tenant (V002).
 *
 * <p>Encapsulated rules:
 *
 * <ul>
 *   <li>Login is only allowed if the e-mail is verified and the user is ACTIVE.
 *   <li>Changing the password invalidates future sessions (reflected by updatedAt; sessions are
 *       stateless JWT, they expire naturally).
 * </ul>
 */
public final class User {

    private final UUID id;
    private final UUID tenantId;
    private final Email email;
    private String passwordHash;
    private String displayName;
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

    /** Constructor for a new unverified user, just created at signup. */
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

    /** Changes the display name (Account tab in settings). Rejects blank. */
    public void changeDisplayName(String newDisplayName, Instant now) {
        if (newDisplayName == null || newDisplayName.isBlank()) {
            throw new IllegalArgumentException("displayName cannot be blank");
        }
        this.displayName = newDisplayName.trim();
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

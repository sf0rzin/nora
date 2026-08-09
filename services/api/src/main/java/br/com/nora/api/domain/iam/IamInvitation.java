package br.com.nora.api.domain.iam;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * User invitation to a tenant (US06, ADR 0011).
 *
 * <p>Immutable aggregate — every state transition returns a new instance. The business rules live
 * here: state transitions are valid only when the current status allows them, and expiry is
 * determined by the clock time supplied by the application layer.
 *
 * <p>The aggregate keeps only the {@code tokenHash} (SHA-256 of the raw token), never the token in
 * clear — same pattern as {@link br.com.nora.api.domain.identity.OneTimeToken} and {@link
 * br.com.nora.api.domain.identity.RefreshToken}. The raw token is the invitee's credential for the
 * public accept endpoint; it exists only in memory during creation (to build the e-mail URL) and is
 * never persisted nor logged. A database dump exposes only the hash, unrecoverable.
 *
 * <p>{@code groupIds} can be empty when the invite does not attach the user to any group on accept.
 * {@code acceptedAt} and {@code acceptedUserId} are filled in only on accept.
 */
public record IamInvitation(
        UUID id,
        UUID tenantId,
        String email,
        String tokenHash,
        InvitationStatus status,
        UUID invitedBy,
        Instant invitedAt,
        Instant expiresAt,
        Instant acceptedAt,
        UUID acceptedUserId,
        Set<UUID> groupIds) {

    public IamInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(tokenHash, "tokenHash");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(invitedBy, "invitedBy");
        Objects.requireNonNull(invitedAt, "invitedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** Tells whether the invitation is already past the accept deadline. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Marks the invitation as accepted, returning a new instance. Only allowed from {@code
     * PENDING}.
     */
    public IamInvitation accept(UUID userId, Instant acceptedAt) {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot accept invite from status " + status + "; expected PENDING");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        return new IamInvitation(
                id,
                tenantId,
                email,
                tokenHash,
                InvitationStatus.ACCEPTED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                userId,
                groupIds);
    }

    /**
     * Marks the invitation as revoked, returning a new instance. Only allowed from {@code PENDING}
     * — revoking after accept/expiry is semantically a no-op and is left as an application error to
     * make the intent clear.
     */
    public IamInvitation revoke() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot revoke invite from status " + status + "; expected PENDING");
        }
        return new IamInvitation(
                id,
                tenantId,
                email,
                tokenHash,
                InvitationStatus.REVOKED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                acceptedUserId,
                groupIds);
    }

    /**
     * Marks the invitation as expired, returning a new instance. Used in the on-read expire (lazy)
     * before listing/accepting. Only has effect from {@code PENDING}.
     */
    public IamInvitation markExpired() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot expire invite from status " + status + "; expected PENDING");
        }
        return new IamInvitation(
                id,
                tenantId,
                email,
                tokenHash,
                InvitationStatus.EXPIRED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                acceptedUserId,
                groupIds);
    }
}

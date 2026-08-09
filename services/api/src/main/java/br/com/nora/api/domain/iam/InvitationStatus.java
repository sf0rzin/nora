package br.com.nora.api.domain.iam;

/**
 * User invitation status (US06, ADR 0011).
 *
 * <ul>
 *   <li>{@code PENDING} — created, still within the deadline, not revoked.
 *   <li>{@code ACCEPTED} — accepted; the user was created and attached to the groups.
 *   <li>{@code EXPIRED} — went past expiresAt without being accepted.
 *   <li>{@code REVOKED} — cancelled by someone with IAM permission before the accept.
 * </ul>
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED,
    REVOKED
}

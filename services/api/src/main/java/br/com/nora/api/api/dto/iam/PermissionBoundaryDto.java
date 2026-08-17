package br.com.nora.api.api.dto.iam;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Answer of {@code GET /iam/users/{userId}/boundary} (US44).
 *
 * <p>A user with no boundary answers {@code 200} with {@code policyId} null, not {@code 404}. The
 * two states this endpoint has to keep apart are "this user has no cap", which is the normal state
 * of almost every user, and "there is no such user in your tenant", which is a 404 and must stay
 * one — collapsing them would make the absence of a boundary indistinguishable from the absence of
 * a person.
 *
 * @param userId the user the boundary belongs to, echoed back
 * @param policyId the policy acting as the cap, null when the user has no boundary
 * @param policyName name of that policy, null when the user has no boundary
 * @param attachedBy who set it, null when there is no boundary or that user has been removed
 * @param attachedAt when it was set, null when there is no boundary
 */
public record PermissionBoundaryDto(
        UUID userId,
        UUID policyId,
        String policyName,
        UUID attachedBy,
        OffsetDateTime attachedAt) {

    /** The answer for a user that carries no cap: present, and explicitly empty. */
    public static PermissionBoundaryDto none(UUID userId) {
        return new PermissionBoundaryDto(userId, null, null, null, null);
    }
}

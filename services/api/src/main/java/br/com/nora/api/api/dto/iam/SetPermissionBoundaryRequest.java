package br.com.nora.api.api.dto.iam;

import java.util.UUID;

/**
 * Body of {@code PUT /iam/users/{userId}/boundary} (US44).
 *
 * <p>It takes an existing policy id rather than a document. A boundary written inline would be a
 * policy that no other screen lists, that {@code iam_policy_versions} does not version and that the
 * simulator could not name — three properties the tenant already has for every other policy, lost
 * for the one document that decides what the others cannot exceed.
 *
 * @param policyId policy to use as the cap; must belong to the caller's tenant
 */
public record SetPermissionBoundaryRequest(UUID policyId) {}

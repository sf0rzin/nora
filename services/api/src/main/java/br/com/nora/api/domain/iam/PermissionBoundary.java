package br.com.nora.api.domain.iam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The permission boundary of one user: a policy that CAPS what that user can do (US44, ADR 0049).
 *
 * <p>An action is allowed only when the user's own policies allow it AND this document allows it.
 * The boundary never grants — a principal with no attached policy and the widest boundary in the
 * tenant can still do nothing.
 *
 * <p>It is deliberately NOT an {@link AttachedPolicy}, although it carries the same three fields
 * plus provenance. {@code AttachedPolicy} is the type the evaluator receives as GRANTS, and the two
 * being interchangeable is exactly how a cap ends up in the granting set. A distinct type makes
 * that mistake fail to compile.
 *
 * @param userId the user this caps
 * @param policyId the policy acting as the cap
 * @param policyName its name, as it appears in the IAM screen
 * @param statements the statements of that policy's current document, in document order
 * @param attachedBy who set it, {@code null} when that user has since been removed
 * @param attachedAt when it was first set
 */
public record PermissionBoundary(
        UUID userId,
        UUID policyId,
        String policyName,
        List<PolicyStatement> statements,
        UUID attachedBy,
        OffsetDateTime attachedAt) {

    public PermissionBoundary {
        statements = List.copyOf(statements);
    }
}

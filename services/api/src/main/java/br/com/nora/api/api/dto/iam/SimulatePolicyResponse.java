package br.com.nora.api.api.dto.iam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Answer of {@code POST /iam/simulate} (US43): the decision AND what took it.
 *
 * <p>The decision alone solves half the problem. Whoever is debugging a policy wants to know which
 * statement decided, so the deciding statement travels back verbatim, together with the policy it
 * belongs to and its position in that document.
 *
 * @param userId subject of the simulation, echoed back
 * @param action action that was tested, echoed back
 * @param resource resource ARN that was tested, echoed back
 * @param allowed the decision
 * @param reason one of {@code NO_STATEMENTS}, {@code NO_MATCHING_STATEMENT}, {@code EXPLICIT_DENY},
 *     {@code ALLOW}, {@code ROOT_BYPASS}, {@code BOUNDARY_EXPLICIT_DENY}, {@code
 *     BOUNDARY_NOT_PERMITTED}
 * @param policyId policy holding the deciding statement, null when no statement decided. On the two
 *     {@code BOUNDARY_*} reasons it is the boundary policy, because that is where the statement is
 * @param policyName name of that policy, null when no statement decided
 * @param statementIndex position of the statement inside that policy's document, null when no
 *     statement decided
 * @param statement the deciding statement itself, null when no statement decided
 * @param statementsEvaluated how many statements applied to the user — 0 for a Root, whose bypass
 *     is taken before any policy is read. Counts the user's own statements, not the boundary's
 * @param boundaryPolicyId the policy capping this user, null when the user has no boundary (US44).
 *     Present whether or not the cap decided, so the answer says "this user is bounded" even when
 *     something else denied first
 * @param boundaryPolicyName name of that policy, null when the user has no boundary
 */
public record SimulatePolicyResponse(
        UUID userId,
        String action,
        String resource,
        boolean allowed,
        String reason,
        UUID policyId,
        String policyName,
        Integer statementIndex,
        Statement statement,
        int statementsEvaluated,
        UUID boundaryPolicyId,
        String boundaryPolicyName) {

    /** The deciding statement, with the field names the policy document itself uses. */
    public record Statement(
            String effect,
            List<String> action,
            List<String> resource,
            Map<String, Object> condition) {}
}

package br.com.nora.api.application.iam;

import br.com.nora.api.domain.iam.PolicyDecision;
import java.util.UUID;

/**
 * A decision plus everything needed to explain it: which policy the deciding statement lives in,
 * where inside that document it sits, and how much was evaluated to get there (US43).
 *
 * @param decision the decision and the statement that produced it
 * @param policyId policy the deciding statement came from, {@code null} when none decided. It is
 *     the BOUNDARY policy when the cap is what decided — {@link PolicyDecision#fromBoundary()}
 *     distinguishes the two cases
 * @param policyName name of that policy, {@code null} when none decided
 * @param statementIndex position of the statement inside that policy's document, {@code null} when
 *     none decided
 * @param statementsEvaluated how many statements applied to the user in total — {@code 0} for a
 *     Root, whose bypass is taken before any policy is read. It counts the user's own statements
 *     and not the boundary's: it answers "how much was attached to this user", which is the
 *     question the number was added for
 * @param boundaryPolicyId the policy capping this user, {@code null} when the user has no boundary.
 *     Reported whether or not the cap is what decided, because "this user is bounded" is itself
 *     part of reading the answer (US44)
 * @param boundaryPolicyName name of that policy, {@code null} when the user has no boundary
 */
public record PolicyExplanation(
        PolicyDecision decision,
        UUID policyId,
        String policyName,
        Integer statementIndex,
        int statementsEvaluated,
        UUID boundaryPolicyId,
        String boundaryPolicyName) {}

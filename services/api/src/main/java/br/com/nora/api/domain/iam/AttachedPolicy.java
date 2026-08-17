package br.com.nora.api.domain.iam;

import java.util.List;
import java.util.UUID;

/**
 * A policy applicable to a user, with its statements still tied to the document they came from.
 *
 * <p>The authorization path flattens every applicable policy into a single list of statements,
 * which is all a yes/no decision needs. A simulation has to say WHERE the deciding statement lives,
 * and a position in the flattened list does not answer that — this record keeps the link.
 *
 * @param policyId id of the policy
 * @param name name of the policy, as it appears in the IAM screen
 * @param statements statements of the policy's current document, in document order
 */
public record AttachedPolicy(UUID policyId, String name, List<PolicyStatement> statements) {

    public AttachedPolicy {
        statements = List.copyOf(statements);
    }
}

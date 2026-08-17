package br.com.nora.api.domain.iam;

/**
 * An authorization decision together with the statement that produced it.
 *
 * <p>It exists so the answer can be EXPLAINED and not only taken (US43). {@link
 * PolicyEvaluator#isAllowed} is {@code explain(...).allowed()} and nothing else, so the explanation
 * shown by a simulation cannot disagree with the decision the request pipeline takes: there is one
 * traversal of the statements, not two.
 *
 * @param allowed the decision itself
 * @param reason why it came out that way
 * @param statementIndex position of the deciding statement in the evaluated list, or {@code null}
 *     when no statement decided
 * @param statement the deciding statement, or {@code null} when no statement decided
 */
public record PolicyDecision(
        boolean allowed, Reason reason, Integer statementIndex, PolicyStatement statement) {

    /** Why a decision came out the way it did. */
    public enum Reason {
        /** No statement applies to the user at all — deny by default. */
        NO_STATEMENTS,
        /** Statements apply and none matched action, resource and condition — deny by default. */
        NO_MATCHING_STATEMENT,
        /** A Deny matched. It wins over every Allow, whatever the order (ADR 0007). */
        EXPLICIT_DENY,
        /** An Allow matched and no Deny did. */
        ALLOW,
        /**
         * The user is the tenant's Root. Never produced by {@link PolicyEvaluator}: the bypass is
         * decided one layer up (ADR 0007, rule 1) and is named here so a simulation reports it
         * instead of answering a mute {@code true} that consulted no statement.
         */
        ROOT_BYPASS
    }

    /** Deny by default: the user has no applicable statement whatsoever. */
    public static PolicyDecision noStatements() {
        return new PolicyDecision(false, Reason.NO_STATEMENTS, null, null);
    }

    /** Deny by default: statements were evaluated and none of them matched. */
    public static PolicyDecision noMatchingStatement() {
        return new PolicyDecision(false, Reason.NO_MATCHING_STATEMENT, null, null);
    }

    /** Deny taken by an explicit Deny statement. */
    public static PolicyDecision explicitDeny(int index, PolicyStatement statement) {
        return new PolicyDecision(false, Reason.EXPLICIT_DENY, index, statement);
    }

    /** Allow taken by a matching Allow statement, with no Deny anywhere. */
    public static PolicyDecision allow(int index, PolicyStatement statement) {
        return new PolicyDecision(true, Reason.ALLOW, index, statement);
    }

    /** Allow taken by the tenant Root bypass, before any statement is consulted. */
    public static PolicyDecision rootBypass() {
        return new PolicyDecision(true, Reason.ROOT_BYPASS, null, null);
    }
}

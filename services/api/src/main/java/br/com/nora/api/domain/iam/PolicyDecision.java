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
 *     when no statement decided. Which list it indexes depends on {@link #fromBoundary()}: the
 *     user's flattened statements normally, the boundary document when the cap decided
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
        ROOT_BYPASS,
        /**
         * The user's own policies allowed it and a Deny inside the PERMISSION BOUNDARY matched
         * (US44, ADR 0049). {@code statementIndex} and {@code statement} point into the boundary
         * document, not into the attached policies.
         */
        BOUNDARY_EXPLICIT_DENY,
        /**
         * The user's own policies allowed it and the PERMISSION BOUNDARY has no Allow covering it
         * (US44, ADR 0049). No statement decided, because a cap denies by not permitting rather
         * than by matching — which is why this is a separate reason from {@link
         * #NO_MATCHING_STATEMENT}: that one sends the reader to the attached policies, this one to
         * the boundary.
         */
        BOUNDARY_NOT_PERMITTED
    }

    /**
     * Whether the deciding statement, if any, lives in the boundary document rather than among the
     * user's attached policies. It is what stops a caller from resolving {@link #statementIndex}
     * against the wrong list of statements.
     */
    public boolean fromBoundary() {
        return reason == Reason.BOUNDARY_EXPLICIT_DENY || reason == Reason.BOUNDARY_NOT_PERMITTED;
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

    /**
     * Re-labels the boundary's OWN evaluation as the cap it is (US44). The argument is the result
     * of running this same evaluator over the boundary document; it decided nothing on its own, and
     * this turns it into the reason the request was capped.
     *
     * <p>The mapping keeps the distinction the boundary's evaluation already made: a Deny that
     * matched is reported with the statement that matched, and an absent Allow is reported without
     * one, because there is no statement to point at. Both are a DENY — a boundary that permits
     * nothing is a boundary, not a bug.
     *
     * @param boundaryDecision what {@link PolicyEvaluator#explain} answered for the boundary
     *     document; it must not be an allow, which the caller has already checked
     */
    public static PolicyDecision cappedByBoundary(PolicyDecision boundaryDecision) {
        if (boundaryDecision.reason() == Reason.EXPLICIT_DENY) {
            return new PolicyDecision(
                    false,
                    Reason.BOUNDARY_EXPLICIT_DENY,
                    boundaryDecision.statementIndex(),
                    boundaryDecision.statement());
        }
        return new PolicyDecision(false, Reason.BOUNDARY_NOT_PERMITTED, null, null);
    }
}

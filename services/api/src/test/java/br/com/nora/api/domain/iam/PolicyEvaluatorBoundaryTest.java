package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * US44 — the permission boundary, at the level where the decision is actually taken.
 *
 * <p>One property carries the whole feature: <b>a boundary caps and never grants</b>. Every test
 * here is an instance of it, and the two that matter most are the pair — an action the user's
 * policies allow and the boundary does not is DENIED, and an action the boundary allows and the
 * user's policies do not is ALSO denied. A second grant path would pass the first and fail the
 * second, so only having both proves it is an intersection.
 */
class PolicyEvaluatorBoundaryTest {

    private static final String READ = "meeting:read";
    private static final String WRITE = "meeting:update";
    private static final String MEETINGS = "nora:tenant/t1:meeting/*";
    private static final String ONE_MEETING = "nora:tenant/t1:meeting/abc";
    private static final String TASKS = "nora:tenant/t1:task/*";

    private static PolicyStatement allow(String action, String resource) {
        return new PolicyStatement(Effect.ALLOW, List.of(action), List.of(resource), null);
    }

    private static PolicyStatement deny(String action, String resource) {
        return new PolicyStatement(Effect.DENY, List.of(action), List.of(resource), null);
    }

    private static PolicyDecision explain(
            List<PolicyStatement> own, List<PolicyStatement> boundary, String action) {
        return PolicyEvaluator.explain(own, boundary, action, ONE_MEETING, Map.of());
    }

    /* ============ the pair that proves it is an intersection ============ */

    @Test
    void allowedByThePoliciesAndNotByTheBoundaryIsDenied() {
        var own = List.of(allow("meeting:*", MEETINGS));
        var boundary = List.of(allow(READ, MEETINGS));

        assertThat(PolicyEvaluator.isAllowed(own, boundary, READ, ONE_MEETING, Map.of())).isTrue();
        assertThat(PolicyEvaluator.isAllowed(own, boundary, WRITE, ONE_MEETING, Map.of()))
                .isFalse();
        assertThat(explain(own, boundary, WRITE).reason())
                .isEqualTo(PolicyDecision.Reason.BOUNDARY_NOT_PERMITTED);
    }

    @Test
    void allowedByTheBoundaryAndNotByThePoliciesIsDenied() {
        var own = List.of(allow("task:read", TASKS));
        var boundary = List.of(allow("*", "*"));

        var decision = explain(own, boundary, READ);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyDecision.Reason.NO_MATCHING_STATEMENT);
    }

    @Test
    void aPrincipalWithNothingAttachedIsNotRescuedByTheWidestBoundary() {
        var decision = explain(List.of(), List.of(allow("*", "*")), READ);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyDecision.Reason.NO_STATEMENTS);
    }

    /* ===================== an explicit Deny, either side ==================== */

    @Test
    void anExplicitDenyInThePoliciesWinsAndIsTheOneReported() {
        var own = List.of(allow("meeting:*", MEETINGS), deny(READ, ONE_MEETING));
        var boundary = List.of(allow("*", "*"));

        var decision = explain(own, boundary, READ);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyDecision.Reason.EXPLICIT_DENY);
        assertThat(decision.fromBoundary()).isFalse();
        assertThat(decision.statementIndex()).isEqualTo(1);
    }

    @Test
    void anExplicitDenyInTheBoundaryWinsAndPointsIntoTheBoundary() {
        var own = List.of(allow("meeting:*", MEETINGS));
        var boundary = List.of(allow("*", "*"), deny(READ, ONE_MEETING));

        var decision = explain(own, boundary, READ);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyDecision.Reason.BOUNDARY_EXPLICIT_DENY);
        assertThat(decision.fromBoundary()).isTrue();
        assertThat(decision.statementIndex()).isEqualTo(1);
        assertThat(decision.statement()).isSameAs(boundary.get(1));
    }

    /* ========================= absence and emptiness ======================== */

    @Test
    void noBoundaryChangesNothing() {
        var own = List.of(allow("meeting:*", MEETINGS));

        assertThat(PolicyEvaluator.isAllowed(own, null, READ, ONE_MEETING, Map.of())).isTrue();
        assertThat(explain(own, null, READ))
                .isEqualTo(PolicyEvaluator.explain(own, READ, ONE_MEETING, Map.of()));
    }

    /**
     * An empty boundary is not the same thing as no boundary. It cannot be reached through the API
     * — the document parser refuses a policy with zero statements — and it is defined as a cap that
     * permits nothing, because between two unreachable readings the one that costs access is the
     * safe one.
     */
    @Test
    void anEmptyBoundaryPermitsNothing() {
        var own = List.of(allow("meeting:*", MEETINGS));

        var decision = explain(own, List.of(), READ);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyDecision.Reason.BOUNDARY_NOT_PERMITTED);
        assertThat(decision.statement()).isNull();
        assertThat(decision.statementIndex()).isNull();
    }

    /* ============================== conditions ============================= */

    @Test
    void aConditionInsideTheBoundaryIsEvaluatedWithTheSameContext() {
        var own = List.of(allow(READ, MEETINGS));
        var boundary =
                List.of(
                        new PolicyStatement(
                                Effect.ALLOW,
                                List.of(READ),
                                List.of(MEETINGS),
                                Map.of("StringEquals", Map.of("department", "Vendas"))));

        assertThat(
                        PolicyEvaluator.isAllowed(
                                own, boundary, READ, ONE_MEETING, Map.of("department", "Vendas")))
                .isTrue();
        assertThat(
                        PolicyEvaluator.isAllowed(
                                own, boundary, READ, ONE_MEETING, Map.of("department", "Suporte")))
                .isFalse();
    }

    /** Fail-closed applies to the cap as well: an operator it does not know permits nothing. */
    @Test
    void anUnsupportedOperatorInTheBoundaryCapsEverything() {
        var own = List.of(allow(READ, MEETINGS));
        var boundary =
                List.of(
                        new PolicyStatement(
                                Effect.ALLOW,
                                List.of(READ),
                                List.of(MEETINGS),
                                Map.of("StringNotEquals", Map.of("department", "Suporte"))));

        assertThat(
                        PolicyEvaluator.isAllowed(
                                own, boundary, READ, ONE_MEETING, Map.of("department", "Vendas")))
                .isFalse();
    }

    /* ===================== the property US43 pinned, with a cap ==================== */

    @Test
    void explainAndIsAllowedNeverDisagreeUnderABoundary() {
        List<List<PolicyStatement>> owns =
                List.of(
                        List.of(),
                        List.of(allow(READ, MEETINGS)),
                        List.of(allow("meeting:*", MEETINGS), deny(READ, ONE_MEETING)));
        List<List<PolicyStatement>> boundaries =
                List.of(
                        List.of(),
                        List.of(allow("*", "*")),
                        List.of(allow(READ, MEETINGS)),
                        List.of(allow("*", "*"), deny(READ, ONE_MEETING)));

        for (List<PolicyStatement> own : owns) {
            for (List<PolicyStatement> boundary : boundaries) {
                assertThat(explain(own, boundary, READ).allowed())
                        .isEqualTo(
                                PolicyEvaluator.isAllowed(
                                        own, boundary, READ, ONE_MEETING, Map.of()));
            }
        }
    }

    /* ============================ the listing paths =========================== */

    @Test
    void theListPreCheckAsksTheBoundaryTheSameWeakQuestion() {
        var own = List.of(allow("meeting:*", MEETINGS));
        var wide = List.of(allow("*", "*"));
        var onMeetings = List.of(allow(READ, MEETINGS));
        var onTasks = List.of(allow("task:read", TASKS));

        assertThat(PolicyEvaluator.hasAnyAllow(own, null, READ, MEETINGS)).isTrue();
        assertThat(PolicyEvaluator.hasAnyAllow(own, onMeetings, READ, MEETINGS)).isTrue();
        assertThat(PolicyEvaluator.hasAnyAllow(own, onTasks, READ, MEETINGS)).isFalse();
        // A user whose own policies say nothing is refused before the boundary is consulted.
        assertThat(PolicyEvaluator.hasAnyAllow(List.of(), wide, READ, MEETINGS)).isFalse();
    }

    @Test
    void theListingShortcutIsTheIntersectionOfBothUniformAnswers() {
        var own = List.of(allow("meeting:*", MEETINGS));
        var wide = List.of(allow("*", "*"));
        var narrow = List.of(allow("task:read", TASKS));
        var perItem = List.of(allow(READ, "nora:tenant/t1:meeting/abc*"));

        assertThat(PolicyEvaluator.uniformDecision(own, null, READ, MEETINGS))
                .isEqualTo(Optional.of(true));
        assertThat(PolicyEvaluator.uniformDecision(own, wide, READ, MEETINGS))
                .isEqualTo(Optional.of(true));
        assertThat(PolicyEvaluator.uniformDecision(own, narrow, READ, MEETINGS))
                .isEqualTo(Optional.of(false));
        // A boundary that distinguishes members of the set forces item-by-item evaluation, which
        // is where the cap is applied per row.
        assertThat(PolicyEvaluator.uniformDecision(own, perItem, READ, MEETINGS)).isEmpty();
        // A uniform deny on the user's own statements stands: no boundary can grant it back.
        assertThat(PolicyEvaluator.uniformDecision(narrow, wide, READ, MEETINGS))
                .isEqualTo(Optional.of(false));
        // And when the user's own statements already need item-by-item evaluation, the boundary
        // is not even asked — the fallback path applies it per row anyway.
        assertThat(PolicyEvaluator.uniformDecision(perItem, wide, READ, MEETINGS)).isEmpty();
        // An empty boundary is a cap that permits nothing, so the whole set is uniformly denied.
        assertThat(PolicyEvaluator.uniformDecision(own, List.of(), READ, MEETINGS))
                .isEqualTo(Optional.of(false));
    }
}

package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code uniformDecision} lets GET /meetings paginate in SQL instead of scanning the whole tenant.
 * If it answers "uniform" when a policy actually distinguishes two meetings, the endpoint starts
 * showing a meeting the user cannot see -- or hiding one they can. These tests pin the only
 * property that makes the optimization acceptable: it agrees, item by item, with the {@code
 * isAllowed} it replaces.
 */
class PolicyEvaluatorUniformDecisionTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final String WILDCARD = "nora:tenant/" + TENANT + ":meeting/*";
    private static final String ACTION = "meeting:read";

    private static String meeting(String id) {
        return "nora:tenant/" + TENANT + ":meeting/" + id;
    }

    private static PolicyStatement allow(String resource) {
        return new PolicyStatement(Effect.ALLOW, List.of(ACTION), List.of(resource), Map.of());
    }

    private static PolicyStatement deny(String resource) {
        return new PolicyStatement(Effect.DENY, List.of(ACTION), List.of(resource), Map.of());
    }

    private static PolicyStatement allowIf(String resource, Map<String, Object> condition) {
        return new PolicyStatement(Effect.ALLOW, List.of(ACTION), List.of(resource), condition);
    }

    // ----------------------------------------------------------------- uniform

    @Test
    void broadAllowIsUniformlyTrue() {
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow(WILDCARD)), ACTION, WILDCARD))
                .contains(true);
    }

    @Test
    void starResourceIsUniformlyTrue() {
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow("*")), ACTION, WILDCARD))
                .contains(true);
    }

    @Test
    void tenantWideWildcardIsUniformlyTrue() {
        String tenantWide = "nora:tenant/" + TENANT + ":*";
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow(tenantWide)), ACTION, WILDCARD))
                .contains(true);
    }

    @Test
    void noStatementAtAllIsUniformlyFalse() {
        assertThat(PolicyEvaluator.uniformDecision(List.of(), ACTION, WILDCARD)).contains(false);
    }

    @Test
    void statementForAnotherResourceTypeDoesNotBlockTheFastPath() {
        // task/* never matches a meeting: irrelevant, not discriminating.
        String tasks = "nora:tenant/" + TENANT + ":task/*";
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), allow(tasks)), ACTION, WILDCARD))
                .contains(true);
    }

    @Test
    void statementForAnotherActionDoesNotBlockTheFastPath() {
        PolicyStatement otherAction =
                new PolicyStatement(
                        Effect.ALLOW,
                        List.of("task:write"),
                        List.of(meeting("abc")),
                        Map.of("StringEquals", Map.of("dept", "eng")));
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), otherAction), ACTION, WILDCARD))
                .contains(true);
    }

    // -------------------------------------------------------------- not uniform

    @Test
    void aConditionForcesPerItemEvaluation() {
        // The condition reads meeting attributes: it changes from item to item.
        List<PolicyStatement> stmts =
                List.of(allowIf(WILDCARD, Map.of("StringEquals", Map.of("dept", "eng"))));
        assertThat(PolicyEvaluator.uniformDecision(stmts, ACTION, WILDCARD)).isEmpty();
    }

    @Test
    void aResourceNarrowerThanTheSetForcesPerItemEvaluation() {
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(meeting("abc"))), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aPrefixPatternInsideTheSetForcesPerItemEvaluation() {
        String prefixPattern = "nora:tenant/" + TENANT + ":meeting/abc*";
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), allow(prefixPattern)), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aTargetedDenyForcesPerItemEvaluation() {
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny(meeting("abc"))), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aNonWildcardSetIsNeverUniform() {
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow("*")), ACTION, meeting("abc")))
                .isEmpty();
    }

    // ------------------------------- wildcard in the middle / start (1st version bypass)

    @Test
    void aDenyWithAWildcardBeforeTheDiscriminatingTailForcesPerItemEvaluation() {
        // The 1st version only looked at the literal BEFORE the first wildcard. Here that
        // literal is "nora:tenant/", which the set prefix starts with -- and the tail
        // ":meeting/<id>", which is exactly what discriminates, was never examined. The Deny
        // vanished and the denied meeting showed up in the listing.
        String crossTenantDeny = "nora:tenant/*:meeting/aaaaaaaa-0000-4000-8000-000000000001";
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny(crossTenantDeny)), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aDenyWithALeadingWildcardForcesPerItemEvaluation() {
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny("*1")), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aDenyWithAWildcardInTheResourceTypeForcesPerItemEvaluation() {
        String midWildcard = "nora:tenant/" + TENANT + ":*/aaaaaaaa-0000-4000-8000-000000000001";
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny(midWildcard)), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aQuestionMarkMaskOverTheWholeSetForcesPerItemEvaluation() {
        // Matches EVERY real meeting but does NOT match the sentinel string "<prefix>*". The
        // 1st version decided by evaluating that sentinel, so it answered "allowed" with a Deny
        // that in practice denied everything.
        String uuidMask = "nora:tenant/" + TENANT + ":meeting/????????-????-????-????-????????????";
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny(uuidMask)), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aSingleCharMaskThatMatchesNoRealMeetingForcesPerItemEvaluation() {
        String oneChar = "nora:tenant/" + TENANT + ":meeting/?";
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow(oneChar)), ACTION, WILDCARD))
                .isEmpty();
    }

    @Test
    void aDenyOverTheWholeSetIsUniformlyFalseEvenWithABroadAllow() {
        assertThat(
                        PolicyEvaluator.uniformDecision(
                                List.of(allow(WILDCARD), deny("*")), ACTION, WILDCARD))
                .contains(false);
    }

    // ------------------------------------------------- equivalence with isAllowed

    /**
     * Resource pattern shapes an admin might write. Deliberately includes wildcards at the start,
     * in the middle and '?', which is the family the first version of this optimization classified
     * wrong -- the old test only sampled trailing wildcards and so passed with the bug.
     */
    private static final List<String> PATTERN_CORPUS =
            List.of(
                    "*",
                    "nora:tenant/" + TENANT + ":*",
                    WILDCARD,
                    "nora:tenant/" + TENANT + ":task/*",
                    "nora:tenant/" + TENANT + ":meeting/aaaaaaaa-0000-4000-8000-000000000001",
                    "nora:tenant/" + TENANT + ":meeting/aaa*",
                    "nora:tenant/*:meeting/aaaaaaaa-0000-4000-8000-000000000001",
                    "nora:tenant/" + TENANT + ":*/aaaaaaaa-0000-4000-8000-000000000001",
                    "nora:tenant/" + TENANT + ":meeting/????????-????-????-????-????????????",
                    "nora:tenant/" + TENANT + ":meeting/?",
                    "*1",
                    "*meeting*",
                    "nora:tenant/99999999-9999-4999-8999-999999999999:meeting/*");

    private static final List<String> SAMPLE_IDS =
            List.of(
                    "aaaaaaaa-0000-4000-8000-000000000001",
                    "bbbbbbbb-0000-4000-8000-000000000002",
                    "aaa-outra",
                    "z");

    @Test
    void wheneverItAnswersTheAnswerMatchesIsAllowedForEveryMemberOfTheSet() {
        // The only property that makes the optimization acceptable: whenever uniformDecision
        // answers, the single answer has to match the individual evaluation of ANY meeting of
        // the tenant. Sweeps every pair of the corpus, in both effects -- 2*13*13 = 338 sets.
        int answered = 0;
        for (String p1 : PATTERN_CORPUS) {
            for (String p2 : PATTERN_CORPUS) {
                for (Effect effect : List.of(Effect.ALLOW, Effect.DENY)) {
                    List<PolicyStatement> stmts =
                            List.of(
                                    allow(p1),
                                    new PolicyStatement(
                                            effect, List.of(ACTION), List.of(p2), Map.of()));
                    Optional<Boolean> uniform =
                            PolicyEvaluator.uniformDecision(stmts, ACTION, WILDCARD);
                    if (uniform.isEmpty()) {
                        continue; // fell back to the item-by-item path: always correct
                    }
                    answered++;
                    for (String id : SAMPLE_IDS) {
                        boolean perItem =
                                PolicyEvaluator.isAllowed(stmts, ACTION, meeting(id), Map.of());
                        assertThat(perItem)
                                .as(
                                        "allow(%s) + %s(%s): uniform=%s but item %s gave %s",
                                        p1, effect, p2, uniform.get(), id, perItem)
                                .isEqualTo(uniform.get());
                    }
                }
            }
        }
        // Guard against the optimization dying unnoticed: if a refactor makes it always
        // `empty`, the test above passes vacuously and the endpoint scans the whole tenant again.
        assertThat(answered)
                .as("no set from the corpus took the fast path")
                .isGreaterThan(20);
    }

    @Test
    void theFastPathStillFiresForTheCommonPolicyShapes() {
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow(WILDCARD)), ACTION, WILDCARD))
                .contains(true);
        assertThat(PolicyEvaluator.uniformDecision(List.of(allow("*")), ACTION, WILDCARD))
                .contains(true);
    }
}

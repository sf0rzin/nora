package br.com.nora.api.domain.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code uniformDecision} deixa o GET /meetings paginar no SQL em vez de varrer o tenant inteiro.
 * Se ele responder "uniforme" quando na verdade uma policy distingue duas reunioes, o endpoint
 * passa a mostrar reuniao que o usuario nao pode ver -- ou a esconder reuniao que ele pode. Estes
 * testes fixam a unica propriedade que torna a otimizacao aceitavel: ela concorda, item a item, com
 * o {@code isAllowed} que substitui.
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

    // ----------------------------------------------------------------- uniforme

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
        // task/* nunca casa uma reuniao: irrelevante, nao discriminante.
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

    // -------------------------------------------------------------- nao uniforme

    @Test
    void aConditionForcesPerItemEvaluation() {
        // A condition le atributos da reuniao: muda de item para item.
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

    // ------------------------------------------------- equivalencia com isAllowed

    @Test
    void whenUniformTheAnswerMatchesIsAllowedForEveryMemberOfTheSet() {
        // A propriedade que sustenta a otimizacao. Para cada conjunto de statements que se declara
        // uniforme, a resposta unica tem de bater com a avaliacao individual de qualquer reuniao.
        String tenantWide = "nora:tenant/" + TENANT + ":*";
        String tasks = "nora:tenant/" + TENANT + ":task/*";
        List<List<PolicyStatement>> uniformSets =
                List.of(
                        List.of(allow(WILDCARD)),
                        List.of(allow("*")),
                        List.of(allow(tenantWide)),
                        List.of(allow(WILDCARD), allow(tasks)),
                        List.of(deny(WILDCARD)),
                        List.of(allow(WILDCARD), deny(WILDCARD)),
                        List.of());

        List<String> sampleIds =
                List.of("abc", "00000000-0000-4000-8000-000000000001", "zzz-999", "a");

        for (List<PolicyStatement> stmts : uniformSets) {
            Optional<Boolean> uniform = PolicyEvaluator.uniformDecision(stmts, ACTION, WILDCARD);
            assertThat(uniform).as("conjunto deveria ser uniforme: %s", stmts).isPresent();
            for (String id : sampleIds) {
                boolean perItem = PolicyEvaluator.isAllowed(stmts, ACTION, meeting(id), Map.of());
                assertThat(perItem)
                        .as("uniforme=%s mas item %s deu %s", uniform.get(), id, perItem)
                        .isEqualTo(uniform.get());
            }
        }
    }
}

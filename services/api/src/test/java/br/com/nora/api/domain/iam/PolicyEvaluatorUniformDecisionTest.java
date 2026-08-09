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

    // ------------------------------- wildcard no meio / inicio (bypass da 1a versao)

    @Test
    void aDenyWithAWildcardBeforeTheDiscriminatingTailForcesPerItemEvaluation() {
        // A 1a versao so olhava o literal ANTES do primeiro wildcard. Aqui esse literal e
        // "nora:tenant/", que o prefixo do conjunto comeca por -- e a cauda ":meeting/<id>",
        // que e justamente quem discrimina, nunca era examinada. O Deny sumia e a reuniao
        // negada aparecia na listagem.
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
        // Casa TODA reuniao real mas NAO casa a string sentinela "<prefix>*". A 1a versao
        // decidia avaliando essa sentinela, entao respondia "permitido" com um Deny que na
        // pratica negava tudo.
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

    // ------------------------------------------------- equivalencia com isAllowed

    /**
     * Formas de resource pattern que um admin pode escrever. Inclui de proposito wildcard no
     * inicio, no meio e '?', que e a familia que a primeira versao desta otimizacao classificava
     * mal -- o teste antigo so amostrava wildcard no fim e por isso passava com o bug.
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
        // A unica propriedade que torna a otimizacao aceitavel: sempre que uniformDecision
        // responde, a resposta unica tem de bater com a avaliacao individual de QUALQUER reuniao
        // do tenant. Varre todos os pares do corpus, nos dois efeitos -- 2*13*13 = 338 conjuntos.
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
                        continue; // recuou para o caminho item a item: sempre correto
                    }
                    answered++;
                    for (String id : SAMPLE_IDS) {
                        boolean perItem =
                                PolicyEvaluator.isAllowed(stmts, ACTION, meeting(id), Map.of());
                        assertThat(perItem)
                                .as(
                                        "allow(%s) + %s(%s): uniforme=%s mas o item %s deu %s",
                                        p1, effect, p2, uniform.get(), id, perItem)
                                .isEqualTo(uniform.get());
                    }
                }
            }
        }
        // Guarda contra a otimizacao morrer sem ninguem reparar: se um refactor a tornar sempre
        // `empty`, o teste acima passa vacuamente e o endpoint volta a varrer o tenant inteiro.
        assertThat(answered)
                .as("nenhum conjunto do corpus tomou o caminho rapido")
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

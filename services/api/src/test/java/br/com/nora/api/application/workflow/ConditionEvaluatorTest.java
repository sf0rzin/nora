package br.com.nora.api.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.workflow.WorkflowEventContext.ActionItemView;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConditionEvaluatorTest {

    private final ConditionEvaluator evaluator = new ConditionEvaluator();

    private WorkflowEventContext ctx(Integer productivity, Integer confidence) {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                UUID.randomUUID(),
                "Reunião X",
                List.of("renovacao", "Discovery"),
                "resumo",
                "resumo",
                1,
                2,
                1,
                List.of(
                        new ActionItemView("Enviar proposta", "Ana", "HIGH"),
                        new ActionItemView("Follow-up", null, "MEDIUM")),
                productivity,
                confidence,
                "http://localhost:3000/meetings/x",
                OffsetDateTime.now(),
                false);
    }

    @Test
    void productivityScoreBelow_passaQuandoMenor() {
        var eval =
                evaluator.evaluate("productivity_score_below", Map.of("value", 70), ctx(62, null));
        assertThat(eval.passed()).isTrue();
        assertThat(eval.description()).contains("62").contains("70");
    }

    @Test
    void productivityScoreBelow_naoPassaQuandoMaiorOuIgual() {
        assertThat(
                        evaluator
                                .evaluate(
                                        "productivity_score_below",
                                        Map.of("value", 70),
                                        ctx(70, null))
                                .passed())
                .isFalse();
        assertThat(
                        evaluator
                                .evaluate(
                                        "productivity_score_below",
                                        Map.of("value", 70),
                                        ctx(90, null))
                                .passed())
                .isFalse();
    }

    @Test
    void productivityScoreBelow_scoreAusenteNaoSatisfaz() {
        var eval =
                evaluator.evaluate(
                        "productivity_score_below", Map.of("value", 70), ctx(null, null));
        assertThat(eval.passed()).isFalse();
        assertThat(eval.description()).contains("ausente");
    }

    @Test
    void productivityScoreBelow_aceitaValueComoString() {
        var eval =
                evaluator.evaluate(
                        "productivity_score_below", Map.of("value", "70"), ctx(62, null));
        assertThat(eval.passed()).isTrue();
    }

    @Test
    void customerConfidenceBelow_usaScoreDeConfidence() {
        assertThat(
                        evaluator
                                .evaluate(
                                        "customer_confidence_below",
                                        Map.of("value", 60),
                                        ctx(null, 58))
                                .passed())
                .isTrue();
        assertThat(
                        evaluator
                                .evaluate(
                                        "customer_confidence_below",
                                        Map.of("value", 60),
                                        ctx(null, 61))
                                .passed())
                .isFalse();
    }

    @Test
    void tagEquals_caseInsensitive() {
        assertThat(
                        evaluator
                                .evaluate(
                                        "tag_equals", Map.of("value", "RENOVACAO"), ctx(null, null))
                                .passed())
                .isTrue();
        assertThat(
                        evaluator
                                .evaluate(
                                        "tag_equals",
                                        Map.of("value", "inexistente"),
                                        ctx(null, null))
                                .passed())
                .isFalse();
    }

    @Test
    void priorityEquals_casaComQualquerActionItem() {
        assertThat(
                        evaluator
                                .evaluate(
                                        "priority_equals", Map.of("value", "high"), ctx(null, null))
                                .passed())
                .isTrue();
        assertThat(
                        evaluator
                                .evaluate(
                                        "priority_equals", Map.of("value", "LOW"), ctx(null, null))
                                .passed())
                .isFalse();
    }

    @Test
    void condicaoDesconhecidaLancaErro() {
        assertThatThrownBy(() -> evaluator.evaluate("fase_da_lua", Map.of(), ctx(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void paramObrigatorioAusenteLancaErro() {
        assertThatThrownBy(
                        () ->
                                evaluator.evaluate(
                                        "productivity_score_below", Map.of(), ctx(62, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value");
    }
}

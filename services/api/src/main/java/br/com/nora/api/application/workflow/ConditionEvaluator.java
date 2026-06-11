package br.com.nora.api.application.workflow;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Avalia nós de condição contra o {@link WorkflowEventContext}. Sem condição no caminho = sempre
 * dispara (quem decide isso é o walk do engine; aqui só avaliamos um nó). Condições sobre dados
 * ausentes (ex.: reunião sem Productivity Score) avaliam como NÃO satisfeitas, com explicação no
 * log — nunca exceção.
 */
@Component
public class ConditionEvaluator {

    /** Resultado da avaliação + frase PT-BR pronta para o log da execução. */
    public record Evaluation(boolean passed, String description) {}

    public Evaluation evaluate(String type, Map<String, Object> params, WorkflowEventContext ctx) {
        return switch (type) {
            case "productivity_score_below" ->
                    scoreBelow(
                            "Productivity Score",
                            ctx.productivityScore(),
                            requiredInt(params, "value", type));
            case "customer_confidence_below" ->
                    scoreBelow(
                            "Customer Confidence",
                            ctx.customerConfidenceScore(),
                            requiredInt(params, "value", type));
            case "tag_equals" -> tagEquals(params, ctx);
            case "priority_equals" -> priorityEquals(params, ctx);
            default -> throw new IllegalArgumentException("condição desconhecida: " + type);
        };
    }

    private Evaluation scoreBelow(String label, Integer actual, int threshold) {
        if (actual == null) {
            return new Evaluation(
                    false, label + " ausente nesta reunião — condição não satisfeita");
        }
        boolean passed = actual < threshold;
        return new Evaluation(passed, label + " " + actual + (passed ? " < " : " ≥ ") + threshold);
    }

    private Evaluation tagEquals(Map<String, Object> params, WorkflowEventContext ctx) {
        String expected = requiredString(params, "value", "tag_equals");
        boolean passed =
                ctx.tags() != null
                        && ctx.tags().stream().anyMatch(t -> t.equalsIgnoreCase(expected.trim()));
        return new Evaluation(
                passed,
                passed
                        ? "reunião tem a tag \"" + expected + "\""
                        : "reunião não tem a tag \"" + expected + "\"");
    }

    private Evaluation priorityEquals(Map<String, Object> params, WorkflowEventContext ctx) {
        String expected = requiredString(params, "value", "priority_equals").trim();
        boolean passed =
                ctx.actionItems() != null
                        && ctx.actionItems().stream()
                                .anyMatch(
                                        a ->
                                                a.priority() != null
                                                        && a.priority().equalsIgnoreCase(expected));
        return new Evaluation(
                passed,
                passed
                        ? "há action item com prioridade " + expected.toUpperCase()
                        : "nenhum action item com prioridade " + expected.toUpperCase());
    }

    private static int requiredInt(Map<String, Object> params, String key, String type) {
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // cai no throw abaixo
            }
        }
        throw new IllegalArgumentException(
                "condição '" + type + "' precisa de params." + key + " numérico");
    }

    private static String requiredString(Map<String, Object> params, String key, String type) {
        Object raw = params.get(key);
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException(
                "condição '" + type + "' precisa de params." + key + " textual");
    }
}

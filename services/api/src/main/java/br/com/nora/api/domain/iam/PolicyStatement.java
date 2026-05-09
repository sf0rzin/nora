package br.com.nora.api.domain.iam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Statement IAM estilo AWS.
 *
 * <p>O campo {@code condition} e armazenado integralmente mas <b>nao avaliado</b> no MVP do
 * PolicyEvaluator (apenas Action+Resource sao testados). Quando avaliacao de conditions for
 * implementada, esta classe ja carrega o mapa.
 */
public record PolicyStatement(
        Effect effect,
        List<String> actions,
        List<String> resources,
        Map<String, Object> condition) {

    public PolicyStatement {
        Objects.requireNonNull(effect, "effect required");
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        actions = List.copyOf(actions);
        resources = List.copyOf(resources);
        condition = condition == null ? Collections.emptyMap() : Map.copyOf(condition);
    }
}

package br.com.nora.api.domain.iam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Statement IAM estilo AWS.
 *
 * <p>O campo {@code condition} mapeia operador -&gt; ({@code chave} -&gt; valor esperado). O {@link
 * PolicyEvaluator} avalia {@code StringEquals}, {@code StringIn}, {@code StringLike}, {@code
 * DateGreaterThan} e {@code DateLessThan}; operadores fora dessa lista sao fail-closed.
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

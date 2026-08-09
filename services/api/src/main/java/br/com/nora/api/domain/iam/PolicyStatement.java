package br.com.nora.api.domain.iam;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AWS-style IAM statement.
 *
 * <p>The {@code condition} field maps operator -&gt; ({@code chave} -&gt; expected value). The
 * {@link PolicyEvaluator} evaluates {@code StringEquals}, {@code StringIn}, {@code StringLike},
 * {@code DateGreaterThan} and {@code DateLessThan}; operators outside that list are fail-closed.
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

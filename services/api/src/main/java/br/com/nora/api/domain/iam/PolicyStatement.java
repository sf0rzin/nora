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
 *
 * <p>The caps on {@code actions} and {@code resources} exist for the reason spelled out in {@link
 * PolicyDocument}: the evaluator crosses both lists per decision, so the real cost of a policy is
 * statements × actions × resources, and bounding only the outer number bounds nothing.
 */
public record PolicyStatement(
        Effect effect,
        List<String> actions,
        List<String> resources,
        Map<String, Object> condition) {

    /** Ceiling on the entries of each of the two lists of one statement. */
    public static final int MAX_ENTRIES_PER_LIST = 100;

    public PolicyStatement {
        Objects.requireNonNull(effect, "effect required");
        if (actions == null || actions.isEmpty()) {
            throw new IllegalArgumentException("actions must not be empty");
        }
        if (resources == null || resources.isEmpty()) {
            throw new IllegalArgumentException("resources must not be empty");
        }
        if (actions.size() > MAX_ENTRIES_PER_LIST) {
            throw new IllegalArgumentException(
                    "a statement must have at most " + MAX_ENTRIES_PER_LIST + " actions");
        }
        if (resources.size() > MAX_ENTRIES_PER_LIST) {
            throw new IllegalArgumentException(
                    "a statement must have at most " + MAX_ENTRIES_PER_LIST + " resources");
        }
        actions = List.copyOf(actions);
        resources = List.copyOf(resources);
        condition = condition == null ? Collections.emptyMap() : Map.copyOf(condition);
    }
}

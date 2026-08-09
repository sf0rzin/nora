package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.PolicyEvaluator;
import br.com.nora.api.domain.iam.PolicyStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Authorization evaluator for the application. Applies the Root bypass and routes to the {@link
 * PolicyEvaluator}.
 */
@Service
public class AuthorizationService {

    private final UserRepository users;
    private final IamRepository iam;

    public AuthorizationService(UserRepository users, IamRepository iam) {
        this.users = users;
        this.iam = iam;
    }

    public boolean isAllowed(UUID userId, UUID tenantId, String action, String resource) {
        return isAllowed(userId, tenantId, action, resource, Map.of());
    }

    /**
     * Version with request context (used for conditions that depend on attributes of the resource,
     * the user or the request).
     */
    public boolean isAllowed(
            UUID userId,
            UUID tenantId,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (users.isRoot(userId, tenantId)) {
            return true;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return PolicyEvaluator.isAllowed(stmts, action, resource, requestContext);
    }

    /** Convenience: throws {@link IamException#forbidden} if authorization fails. */
    public void require(UUID userId, UUID tenantId, String action, String resource) {
        require(userId, tenantId, action, resource, Map.of());
    }

    /** Convenience with request context: throws {@link IamException#forbidden} if denied. */
    public void require(
            UUID userId,
            UUID tenantId,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (!isAllowed(userId, tenantId, action, resource, requestContext)) {
            throw IamException.forbidden(action);
        }
    }

    /**
     * Pre-check without conditions: ensures the user has at least one Allow for action+resource,
     * ignoring conditions. Ideal for list-endpoints where conditions are evaluated per item.
     */
    public void requireAnyAllow(UUID userId, UUID tenantId, String action, String resource) {
        if (users.isRoot(userId, tenantId)) {
            return;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        if (!PolicyEvaluator.hasAnyAllow(stmts, action, resource)) {
            throw IamException.forbidden(action);
        }
    }

    /**
     * Answers, BEFORE hitting the database, whether the decision for {@code action} is the same for
     * every resource covered by {@code wildcardResource}. Present = can decide in one go; {@code
     * empty} = really has to evaluate item by item with {@link #filterAllowed}.
     *
     * <p>Serves listings. Filtering item by item forces loading the whole set before paginating;
     * when no statement distinguishes two items of the set, that scan always produces the same
     * answer and the pagination can stay in SQL.
     *
     * <p>Root lands here as a uniform allow, which is literally what {@link #filterAllowed} does
     * with it: returns the list intact. Before this, the listing endpoint scanned the entire tenant
     * in order to filter nothing.
     */
    public Optional<Boolean> uniformDecision(
            UUID userId, UUID tenantId, String action, String wildcardResource) {
        if (users.isRoot(userId, tenantId)) {
            return Optional.of(true);
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return PolicyEvaluator.uniformDecision(stmts, action, wildcardResource);
    }

    /**
     * Filters a collection in-memory by permission, resolving the Root bypass and the user's
     * statements ONE SINGLE time for the whole set. Use in list-endpoints with per-item conditions:
     * avoids the N+1 of calling {@link #isAllowed} per element (which re-queries {@code isRoot} +
     * {@code collectStatementsForUser} on every call). The evaluation semantics are identical —
     * same {@link PolicyEvaluator}, same Root bypass.
     *
     * @param resourceFn resource ARN for each item
     * @param contextFn resource attributes (conditions) for each item
     */
    public <T> List<T> filterAllowed(
            UUID userId,
            UUID tenantId,
            String action,
            List<T> items,
            java.util.function.Function<T, String> resourceFn,
            java.util.function.Function<T, Map<String, String>> contextFn) {
        if (users.isRoot(userId, tenantId)) {
            return items;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        return items.stream()
                .filter(
                        it ->
                                PolicyEvaluator.isAllowed(
                                        stmts, action, resourceFn.apply(it), contextFn.apply(it)))
                .toList();
    }
}

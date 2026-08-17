package br.com.nora.api.application.iam;

import br.com.nora.api.application.ports.IamRepository;
import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.iam.AttachedPolicy;
import br.com.nora.api.domain.iam.PermissionBoundary;
import br.com.nora.api.domain.iam.PolicyDecision;
import br.com.nora.api.domain.iam.PolicyEvaluator;
import br.com.nora.api.domain.iam.PolicyStatement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Authorization evaluator for the application. Applies the Root bypass, resolves the principal's
 * permission boundary and routes to the {@link PolicyEvaluator}.
 *
 * <p><strong>The Root bypass runs before the boundary, and that is a decision (ADR 0049
 * §2).</strong> A boundary on a Root would be a lock with no key: the only principal that can
 * remove it is the one it silences, there is no support desk behind this product, and a tenant that
 * capped its own Root out of {@code iam:*} could never undo it. AWS makes the same call for the
 * account root. {@link IamService} refuses to write such a row at all, so the bypass is not quietly
 * ignoring one.
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
        List<PolicyStatement> boundary = boundaryStatements(userId, tenantId);
        return PolicyEvaluator.isAllowed(stmts, boundary, action, resource, requestContext);
    }

    /**
     * The statements of the user's permission boundary, or {@code null} when there is none (US44).
     *
     * <p>{@code null} is the value the evaluator reads as UNRESTRICTED. It is produced here in one
     * place, from an {@link java.util.Optional} that is empty exactly when no row exists, so no
     * caller has to decide what absence means — which is the failure mode ADR 0049 §5 is about.
     */
    private List<PolicyStatement> boundaryStatements(UUID userId, UUID tenantId) {
        return iam.findBoundaryForUser(userId, tenantId)
                .map(PermissionBoundary::statements)
                .orElse(null);
    }

    /**
     * The same decision {@link #isAllowed} takes, with its reason and the statement that took it
     * (US43). It exists so a policy can be debugged without attempting the real operation and
     * reading the 403 back.
     *
     * <p>The Root bypass is REPORTED here, not applied silently. {@code isAllowed} answers {@code
     * true} on its first line for a Root, which is correct and useless as an explanation, because
     * no statement was ever consulted; a simulation has to say exactly that instead.
     *
     * <p>Nothing is re-evaluated: {@link PolicyEvaluator#isAllowed} delegates to {@link
     * PolicyEvaluator#explain}, and both collectors read one query, so decision and explanation
     * come from a single traversal of a single set of statements.
     *
     * <p>The permission boundary is reported the same way (US44). When the cap is what denied, the
     * reason says so and the named policy is the BOUNDARY policy — a simulator that answered "no
     * statement matched" while the gate was refusing on a cap would send whoever is debugging to
     * read the wrong document.
     */
    public PolicyExplanation explain(
            UUID userId,
            UUID tenantId,
            String action,
            String resource,
            Map<String, String> requestContext) {
        if (users.isRoot(userId, tenantId)) {
            return new PolicyExplanation(
                    PolicyDecision.rootBypass(), null, null, null, 0, null, null);
        }
        List<AttachedPolicy> attached = iam.collectAttachedPoliciesForUser(userId, tenantId);
        List<PolicyStatement> stmts = flatten(attached);
        PermissionBoundary boundary = iam.findBoundaryForUser(userId, tenantId).orElse(null);
        List<PolicyStatement> capStatements = boundary == null ? null : boundary.statements();
        PolicyDecision decision =
                PolicyEvaluator.explain(stmts, capStatements, action, resource, requestContext);
        return locate(attached, boundary, decision, stmts.size());
    }

    /** The applicable statements in the very order {@code explain} indexes into. */
    private static List<PolicyStatement> flatten(List<AttachedPolicy> attached) {
        return attached.stream().flatMap(p -> p.statements().stream()).toList();
    }

    /**
     * Maps the index of the deciding statement back to the policy document it came from.
     *
     * <p>Two different lists can hold that statement, and {@link PolicyDecision#fromBoundary()} is
     * what says which. A boundary decision indexes into the boundary document alone, so walking the
     * attached policies for it would name whichever policy happened to sit at that offset — a
     * confident, wrong answer, which is the one thing a simulator must not produce.
     */
    private static PolicyExplanation locate(
            List<AttachedPolicy> attached,
            PermissionBoundary boundary,
            PolicyDecision decision,
            int total) {
        UUID boundaryId = boundary == null ? null : boundary.policyId();
        String boundaryName = boundary == null ? null : boundary.policyName();
        Integer index = decision.statementIndex();
        if (decision.fromBoundary()) {
            return new PolicyExplanation(
                    decision, boundaryId, boundaryName, index, total, boundaryId, boundaryName);
        }
        if (index != null) {
            int seen = 0;
            for (AttachedPolicy p : attached) {
                if (index < seen + p.statements().size()) {
                    return new PolicyExplanation(
                            decision,
                            p.policyId(),
                            p.name(),
                            index - seen,
                            total,
                            boundaryId,
                            boundaryName);
                }
                seen += p.statements().size();
            }
        }
        return new PolicyExplanation(decision, null, null, null, total, boundaryId, boundaryName);
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
     *
     * <p>The boundary is asked the same weak question (US44). It cannot make this stricter than the
     * per-item filter that follows — a cap admitting no member of the set denies every row anyway —
     * and applying it here turns a listing a bounded user may not read into a clean 403 instead of
     * an empty page.
     */
    public void requireAnyAllow(UUID userId, UUID tenantId, String action, String resource) {
        if (users.isRoot(userId, tenantId)) {
            return;
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        List<PolicyStatement> boundary = boundaryStatements(userId, tenantId);
        if (!PolicyEvaluator.hasAnyAllow(stmts, boundary, action, resource)) {
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
     *
     * <p>The boundary is part of the answer and not an afterthought (US44). A uniform allow tells
     * the caller it may skip {@link #filterAllowed} entirely, so a cap consulted only inside that
     * filter would be skipped with it — the one shape in which a boundary silently stops applying.
     */
    public Optional<Boolean> uniformDecision(
            UUID userId, UUID tenantId, String action, String wildcardResource) {
        if (users.isRoot(userId, tenantId)) {
            return Optional.of(true);
        }
        List<PolicyStatement> stmts = iam.collectStatementsForUser(userId, tenantId);
        List<PolicyStatement> boundary = boundaryStatements(userId, tenantId);
        return PolicyEvaluator.uniformDecision(stmts, boundary, action, wildcardResource);
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
        List<PolicyStatement> boundary = boundaryStatements(userId, tenantId);
        return items.stream()
                .filter(
                        it ->
                                PolicyEvaluator.isAllowed(
                                        stmts,
                                        boundary,
                                        action,
                                        resourceFn.apply(it),
                                        contextFn.apply(it)))
                .toList();
    }
}

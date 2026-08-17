package br.com.nora.api.domain.iam;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The built-in policy templates (US41), shipped in code and rendered for one tenant.
 *
 * <p><strong>Templates are not rows, and this is the decision.</strong> The old design foresaw an
 * {@code is_template} flag on {@code iam_policies}; the migration never carried it. It is not
 * added, for two reasons. A template's job is the FIRST policy of a tenant that has none, so a
 * per-tenant table is empty exactly when the feature is needed and would still have to be seeded
 * from a catalogue written in code — two copies of the same list, drifting on the next edit. And a
 * flagged row is still a policy id: {@code POST /iam/users/{userId}/policies/{policyId}} attaches
 * whatever id it is given, so a template row would be attachable as a live grant unless the attach
 * path learned to read the flag. That is a new branch in the authorization write path, which is the
 * one place this system must not grow branches.
 *
 * <p>The cost, stated rather than hidden: a code-shipped catalogue cannot be customised per tenant
 * and there is no "save as template". What a tenant gets instead is that instantiating produces an
 * ordinary policy — editable, versioned and audited through the endpoints that already exist — so a
 * house starting point is a policy they copy, not a second concept.
 *
 * <p>ARNs are built here rather than through {@code ResourceArns}, which lives in the {@code api}
 * layer and cannot be imported by {@code domain} (§2 of the architecture). The duplication is
 * deliberate and guarded: {@code PolicyTemplateCatalogTest} asserts each format below equals what
 * {@code ResourceArns} produces, so a drift fails a test instead of silently granting nothing.
 */
public final class PolicyTemplateCatalog {

    /** Document version stamped on every template, the same one the API and the UI use. */
    private static final String DOCUMENT_VERSION = "2026-05-07";

    /**
     * The value shipped in the one template that carries a condition. It matches no real
     * department, so that template grants nothing until the value is replaced — an unsatisfied
     * condition is fail-closed, exactly like every other one.
     */
    public static final String CONDITION_PLACEHOLDER = "CHANGE-ME";

    private PolicyTemplateCatalog() {}

    /**
     * The catalogue, with every ARN already bound to {@code tenantId}. The order is stable so the
     * UI does not reshuffle between reloads.
     */
    public static List<PolicyTemplate> forTenant(UUID tenantId) {
        Objects.requireNonNull(tenantId, "tenantId required");
        return List.of(
                readOnlyAccess(tenantId),
                meetingAnalyst(tenantId),
                iamAdministrator(tenantId),
                departmentScopedMeetingReader(tenantId));
    }

    /**
     * Reads across the product, IAM excluded. AWS folds IAM reads into its own ReadOnlyAccess; here
     * they are left out on purpose, because the policies and the audit trail are the tenant's
     * access-control record and handing them to every read-only member is a wider grant than the
     * name suggests. {@link #iamAdministrator} is where they live.
     */
    private static PolicyTemplate readOnlyAccess(UUID tenantId) {
        return new PolicyTemplate(
                "read-only-access",
                "Read access to meetings, tasks, settings, flows and integrations.",
                document(
                        allow(
                                List.of(
                                        "meeting:read",
                                        "task:read",
                                        "tenant:read",
                                        "tenant:context:read",
                                        "workflow:read",
                                        "integration:read"),
                                List.of(tenant(tenantId), everythingIn(tenantId)))));
    }

    /**
     * The working set for someone who runs meetings: everything on a meeting except starting a live
     * capture, which spends money with an external provider and is left to an explicit grant.
     */
    private static PolicyTemplate meetingAnalyst(UUID tenantId) {
        return new PolicyTemplate(
                "meeting-analyst",
                "Upload, read, update and reprocess meetings, and write their tasks.",
                document(
                        allow(
                                List.of(
                                        "meeting:upload",
                                        "meeting:read",
                                        "meeting:update",
                                        "meeting:reprocess",
                                        "task:read",
                                        "task:write"),
                                List.of(meetings(tenantId), tasks(tenantId)))));
    }

    /**
     * Every IAM operation, including {@code iam:policy:simulate}, which the {@code iam:*} shape
     * picks up on its own. Invitations are a second ARN because they are keyed by invite id, not by
     * the IAM scope.
     */
    private static PolicyTemplate iamAdministrator(UUID tenantId) {
        return new PolicyTemplate(
                "iam-administrator",
                "Every IAM operation: groups, policies, attachments, invites, audit.",
                document(allow(List.of("iam:*"), List.of(iam(tenantId), invites(tenantId)))));
    }

    /**
     * The shape of an attribute-scoped grant, which is the half of the policy language the other
     * templates never show. The condition reads {@code department} out of the meeting's attributes
     * (migration V007); a meeting without that attribute does not satisfy it.
     */
    private static PolicyTemplate departmentScopedMeetingReader(UUID tenantId) {
        Map<String, Object> condition =
                Map.of("StringEquals", Map.of("department", CONDITION_PLACEHOLDER));
        return new PolicyTemplate(
                "department-scoped-meeting-reader",
                "Reads only meetings whose department attribute matches the condition.",
                document(
                        new PolicyStatement(
                                Effect.ALLOW,
                                List.of("meeting:read"),
                                List.of(meetings(tenantId)),
                                condition)));
    }

    private static PolicyDocument document(PolicyStatement... statements) {
        return new PolicyDocument(DOCUMENT_VERSION, List.of(statements));
    }

    /** An unconditional {@code Allow} over every listed action and every listed resource. */
    private static PolicyStatement allow(List<String> actions, List<String> resources) {
        return new PolicyStatement(Effect.ALLOW, actions, resources, Map.of());
    }

    private static String base(UUID tenantId) {
        return "nora:tenant/" + tenantId;
    }

    /** {@code nora:tenant/{t}} — the tenant record itself, the target of {@code tenant:read}. */
    private static String tenant(UUID tenantId) {
        return base(tenantId);
    }

    /**
     * {@code nora:tenant/{t}:*} — every resource of the tenant whatever its type. Wide on the
     * resource side on purpose: what a template of this kind restricts is the ACTION list, and
     * enumerating one ARN per resource type would silently miss the next type to be added.
     */
    private static String everythingIn(UUID tenantId) {
        return base(tenantId) + ":*";
    }

    /** {@code nora:tenant/{t}:meeting/*} — all of the tenant's meetings. */
    private static String meetings(UUID tenantId) {
        return base(tenantId) + ":meeting/*";
    }

    /** {@code nora:tenant/{t}:task/*} — all of the tenant's tasks. */
    private static String tasks(UUID tenantId) {
        return base(tenantId) + ":task/*";
    }

    /** {@code nora:tenant/{t}:iam/*} — the tenant's IAM scope. */
    private static String iam(UUID tenantId) {
        return base(tenantId) + ":iam/*";
    }

    /** {@code nora:tenant/{t}:invite/*} — all of the tenant's invitations. */
    private static String invites(UUID tenantId) {
        return base(tenantId) + ":invite/*";
    }
}

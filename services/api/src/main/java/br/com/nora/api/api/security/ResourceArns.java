package br.com.nora.api.api.security;

import java.util.UUID;

/**
 * Centralized builders for IAM resource ARNs ({@code nora:tenant/{tenantId}:...}).
 *
 * <p>Before, each controller built the string by hand — {@code meetingResource}/{@code
 * taskResource} were duplicated byte-for-byte across controllers. A format drift would silently
 * break the authorization comparison (ADR 0002), so ARN construction lives in a single source here.
 * First step of the IAM refactor (#51): centralize the ARNs before extracting the interceptor.
 */
public final class ResourceArns {

    private ResourceArns() {}

    private static String base(UUID tenantId) {
        return "nora:tenant/" + tenantId;
    }

    /** {@code nora:tenant/{t}:meeting/{id|*}} — {@code *} when {@code meetingId} is null. */
    public static String meeting(UUID tenantId, UUID meetingId) {
        return base(tenantId) + ":meeting/" + (meetingId == null ? "*" : meetingId);
    }

    /** {@code nora:tenant/{t}:task/{id|*}} — {@code *} when {@code taskId} is null. */
    public static String task(UUID tenantId, UUID taskId) {
        return base(tenantId) + ":task/" + (taskId == null ? "*" : taskId);
    }

    /** {@code nora:tenant/{t}:invite/{id|*}} — {@code *} when {@code inviteId} is null. */
    public static String invite(UUID tenantId, UUID inviteId) {
        return base(tenantId) + ":invite/" + (inviteId == null ? "*" : inviteId);
    }

    /** {@code nora:tenant/{t}:workflow/{id|*}} — {@code *} when {@code workflowId} is null. */
    public static String workflow(UUID tenantId, UUID workflowId) {
        return base(tenantId) + ":workflow/" + (workflowId == null ? "*" : workflowId);
    }

    /**
     * {@code nora:tenant/{t}:integration/*} — the tenant's integrations scope. Connections are
     * keyed by provider name, not by UUID, and every handler acts on the whole tenant's hub, so the
     * ARN is the wildcard for the set (same shape as {@link #iamWildcard}).
     */
    public static String integrationWildcard(UUID tenantId) {
        return base(tenantId) + ":integration/*";
    }

    /** {@code nora:tenant/{t}:iam/*} — the tenant's IAM scope. */
    public static String iamWildcard(UUID tenantId) {
        return base(tenantId) + ":iam/*";
    }

    /** {@code nora:tenant/{t}} — the tenant itself. */
    public static String tenant(UUID tenantId) {
        return base(tenantId);
    }

    /** {@code nora:tenant/{t}:tenant/context} — the tenant's context/configuration. */
    public static String tenantContext(UUID tenantId) {
        return base(tenantId) + ":tenant/context";
    }

    /** Builds the ARN from the resource type declared in {@link RequiresPermission}. */
    public static String of(RequiresPermission.ResourceType type, UUID tenantId, UUID id) {
        return switch (type) {
            case MEETING -> meeting(tenantId, id);
            case TASK -> task(tenantId, id);
            case INVITE -> invite(tenantId, id);
            case IAM -> iamWildcard(tenantId);
            case TENANT -> tenant(tenantId);
            case TENANT_CONTEXT -> tenantContext(tenantId);
            case WORKFLOW -> workflow(tenantId, id);
            case INTEGRATION -> integrationWildcard(tenantId);
        };
    }
}

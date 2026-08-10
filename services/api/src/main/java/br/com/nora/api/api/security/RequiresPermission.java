package br.com.nora.api.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the IAM permission required by a controller handler (ADR 0007, #51).
 *
 * <p>The {@code RequiresPermissionInterceptor} resolves the authenticated principal, builds the
 * resource ARN (via {@link ResourceArns}) from the tenant + an optional {@code @PathVariable}, and
 * calls the {@code AuthorizationService} BEFORE the method — replacing the manual {@code
 * authz.require(...)} on endpoints WITHOUT per-attribute conditions.
 *
 * <p>Declaring one of this annotation or {@link AuthorizationNotRequired} is MANDATORY on every
 * handler of this API: the interceptor denies anything that declares neither, so a new endpoint
 * cannot ship without an authorization decision.
 *
 * <p><b>Deliberate limit:</b> endpoints whose Allow depends on resource ATTRIBUTES (e.g.: {@code
 * meeting.attributes()} in get/update/reprocess/erase) keep authorizing explicitly in the body —
 * the interceptor runs before loading the resource, so there is no way to feed the conditions. A
 * condition over an attribute missing from the context makes the statement not match, which is
 * fail-closed for an Allow but silently DROPS a Deny; moving such an endpoint into this annotation
 * would void every attribute-scoped Deny over that resource. Those cases stay with {@code
 * authz.require(..., attributes)} inside the transaction (TOCTOU-safe) plus {@link
 * AuthorizationNotRequired} carrying that reason.
 *
 * <p><b>Listings:</b> use {@link #anyAllow()} as the pre-gate and filter per item in the body with
 * {@code AuthorizationService#filterAllowed}. The strict path builds the literal ARN {@code
 * ...:task/*}, and the {@code *} is matched as plain text on the resource side — a Deny written
 * against one specific id would never fire against it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /** Required IAM action, e.g.: {@code "meeting:read"}. */
    String action();

    /** Resource type used to build the ARN. */
    ResourceType resource();

    /** Name of the {@code @PathVariable} carrying the resource id; empty = wildcard ({@code *}). */
    String idParam() default "";

    /**
     * Pre-check without conditions (uses {@code requireAnyAllow}): guarantees at least one Allow
     * for action+resource ignoring conditions. For list-endpoints where the fine filter is
     * per-item.
     */
    boolean anyAllow() default false;

    /** IAM resource type — mapped to the ARN by {@link ResourceArns#of}. */
    enum ResourceType {
        MEETING,
        TASK,
        INVITE,
        IAM,
        TENANT,
        TENANT_CONTEXT,
        WORKFLOW,
        INTEGRATION
    }
}

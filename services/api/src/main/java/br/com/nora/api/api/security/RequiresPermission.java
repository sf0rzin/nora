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
 * <p><b>Deliberate limit:</b> endpoints whose Allow depends on resource ATTRIBUTES (e.g.: {@code
 * meeting.attributes()} in get/update/reprocess) keep authorizing explicitly in the body — the
 * interceptor runs before loading the resource, so there is no way to feed the conditions. Those
 * cases stay with {@code authz.require(..., attributes)} inside the transaction (TOCTOU-safe).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
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
        TENANT_CONTEXT
    }
}

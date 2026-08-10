package br.com.nora.api.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares — with a written justification — that a controller handler takes NO IAM action (#51).
 *
 * <p>Authorization in NORA is deny-by-default: {@link RequiresPermissionInterceptor} refuses any
 * handler of this API that carries neither {@link RequiresPermission} nor this annotation. That
 * default only works if the escape hatch is expensive to use, so {@link #reason()} is mandatory and
 * an empty reason does NOT count as an opt-out — the interceptor treats it as undeclared and
 * denies.
 *
 * <p>Three categories are legitimate. Anything outside them wants {@link RequiresPermission}:
 *
 * <ol>
 *   <li><b>Public</b> — the path is in {@code SecurityConfig.PUBLIC_ENDPOINTS} and carries its own
 *       credential or rate limit: health, JWKS, the auth flows, invite acceptance, the OAuth
 *       callback. There is no principal to authorize.
 *   <li><b>Principal-scoped ("self")</b> — the handler only ever touches the caller's own row, and
 *       says so by passing {@code principal.userId()} down on every call: {@code /auth/me},
 *       password change, logout-all, {@code PATCH|DELETE /users/me}, {@code /chat/sessions/**}. An
 *       IAM action here would gate a user against himself.
 *   <li><b>Authorizes in the method body</b> — the Allow/Deny depends on the RESOURCE's attributes,
 *       which the interceptor cannot see because it runs before the resource is loaded. These call
 *       {@code authz.require(..., attributes)} after resolving the resource (ideally inside the
 *       service transaction, TOCTOU-safe). See {@link RequiresPermission} for why this boundary
 *       exists and must not be "simplified" into the annotation.
 * </ol>
 *
 * <p>A fourth shape exists but is not an opt-out: list endpoints combine {@code
 * RequiresPermission(anyAllow = true)} as the pre-gate with a per-item {@code
 * AuthorizationService#filterAllowed} in the body. Those keep the annotation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface AuthorizationNotRequired {

    /**
     * Why this handler needs no IAM gate — name the category and the concrete reason. Blank is not
     * an opt-out: the interceptor denies as if the annotation were absent.
     */
    String reason();
}

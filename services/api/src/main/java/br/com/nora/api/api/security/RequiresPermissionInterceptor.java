package br.com.nora.api.api.security;

import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Applies {@link RequiresPermission} before the handler: resolves the principal, builds the
 * resource ARN and calls the {@link AuthorizationService} (require or requireAnyAllow). Runs after
 * the Spring Security filter chain (SecurityContext already populated). The {@code
 * IamException.forbidden} thrown here is routed by the GlobalExceptionHandler to 403 — the same
 * path as the manual authz.require.
 */
@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

    private final AuthorizationService authz;

    // @Lazy: resolving the AuthorizationService is deferred to the 1st use (a request with
    // @RequiresPermission). Without it, @WebMvcTest slices (which do not load @Service) would fail
    // creating this interceptor at context load. In real runtime the bean is always present.
    public RequiresPermissionInterceptor(@Lazy AuthorizationService authz) {
        this.authz = authz;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }
        RequiresPermission ann = hm.getMethodAnnotation(RequiresPermission.class);
        if (ann == null) {
            return true;
        }
        AuthenticatedPrincipal principal = CurrentUser.require();

        UUID resourceId;
        try {
            resourceId = resolveId(request, ann.idParam());
        } catch (IllegalArgumentException malformed) {
            // Malformed path id: let the @PathVariable binding return 400 (the method does not
            // get to run anyway). Does not authorize against a made-up ARN.
            return true;
        }

        String arn = ResourceArns.of(ann.resource(), principal.tenantId(), resourceId);
        if (ann.anyAllow()) {
            authz.requireAnyAllow(principal.userId(), principal.tenantId(), ann.action(), arn);
        } else {
            authz.require(principal.userId(), principal.tenantId(), ann.action(), arn);
        }
        return true;
    }

    private static UUID resolveId(HttpServletRequest request, String idParam) {
        if (idParam == null || idParam.isBlank()) {
            return null;
        }
        Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars instanceof Map<?, ?> map) {
            Object raw = map.get(idParam);
            if (raw != null) {
                return UUID.fromString(raw.toString());
            }
        }
        return null;
    }
}

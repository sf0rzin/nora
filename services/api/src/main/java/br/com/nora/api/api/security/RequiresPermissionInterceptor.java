package br.com.nora.api.api.security;

import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>It is also the DEFAULT DENY for authorization (#51). Authentication was already
 * deny-by-default ({@code anyRequest().authenticated()}), but authorization was not: an
 * authenticated principal reached every handler unless that handler happened to check by hand, so a
 * new controller method shipped ungated and nothing noticed. Here, a handler of this API that
 * declares neither {@link RequiresPermission} nor {@link AuthorizationNotRequired} is REFUSED — the
 * failure mode is a refusal, never an accidental allow, and it is logged at ERROR naming the
 * handler so it reads as the coding mistake it is instead of an ordinary policy denial.
 *
 * <p>The default deny covers only NORA's own handlers ({@code OWN_HANDLER_PACKAGE}). Framework
 * handlers dispatched through the same DispatcherServlet — actuator, springdoc, the error
 * controller — are not part of this API surface and stay gated by {@code SecurityConfig} alone. The
 * two control-plane controllers ARE in this package but run on their own {@code
 * SecurityFilterChain} behind a shared-token filter, with no IAM principal to authorize; they carry
 * a class-level {@link AuthorizationNotRequired} saying exactly that.
 */
@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(RequiresPermissionInterceptor.class);

    /** Package prefix of NORA's own API surface — the scope of the default deny. */
    private static final String OWN_HANDLER_PACKAGE = "br.com.nora.api.";

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
        RequiresPermission ann = findPermission(hm);
        if (ann == null) {
            enforceDefaultDeny(hm);
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

    /**
     * Refuses a handler that declared no authorization decision. Silence is not consent: without
     * this, forgetting the annotation on a new endpoint opens it to every authenticated principal
     * of every tenant.
     */
    private static void enforceDefaultDeny(HandlerMethod hm) {
        if (hasOptOut(hm) || !isOwnHandler(hm)) {
            return;
        }
        String handler = hm.getBeanType().getSimpleName() + "#" + hm.getMethod().getName();
        LOG.error("Authorization not declared by {}; refused by default (#51).", handler);
        throw IamException.authorizationNotDeclared();
    }

    /** {@link RequiresPermission} on the method, falling back to the controller class. */
    private static RequiresPermission findPermission(HandlerMethod hm) {
        RequiresPermission onMethod = hm.getMethodAnnotation(RequiresPermission.class);
        if (onMethod != null) {
            return onMethod;
        }
        return hm.getBeanType().getAnnotation(RequiresPermission.class);
    }

    /**
     * Whether the handler (or its controller) declares a JUSTIFIED opt-out. A blank reason is not
     * an opt-out — that is what keeps {@link AuthorizationNotRequired} from becoming a silent
     * escape hatch.
     */
    private static boolean hasOptOut(HandlerMethod hm) {
        AuthorizationNotRequired m = hm.getMethodAnnotation(AuthorizationNotRequired.class);
        if (m != null) {
            return !m.reason().isBlank();
        }
        Class<?> controller = hm.getBeanType();
        AuthorizationNotRequired c = controller.getAnnotation(AuthorizationNotRequired.class);
        return c != null && !c.reason().isBlank();
    }

    private static boolean isOwnHandler(HandlerMethod hm) {
        return hm.getBeanType().getName().startsWith(OWN_HANDLER_PACKAGE);
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

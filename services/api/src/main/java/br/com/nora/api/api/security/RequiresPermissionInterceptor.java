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
 * Aplica {@link RequiresPermission} antes do handler: resolve o principal, monta o ARN do recurso e
 * chama o {@link AuthorizationService} (require ou requireAnyAllow). Roda depois do filter chain do
 * Spring Security (SecurityContext já populado). A {@code IamException.forbidden} lançada aqui é
 * roteada pelo GlobalExceptionHandler para 403 — mesmo caminho do authz.require manual.
 */
@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

    private final AuthorizationService authz;

    // @Lazy: a resolução do AuthorizationService é adiada para o 1º uso (request com
    // @RequiresPermission). Sem isso, slices @WebMvcTest (que não carregam @Service) falhariam ao
    // criar este interceptor no load do contexto. Em runtime real o bean está sempre presente.
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
            // Id de path malformado: deixa o binding do @PathVariable retornar 400 (o método não
            // chega a rodar mesmo). Não autoriza contra um ARN inventado.
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

package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.TenantRlsContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link TenantRlsContext} adapter that delegates to {@link TenantContextHolder} — the same
 * ThreadLocal that {@code JwtAuthenticationFilter} populates on the request and that {@code
 * TenantRlsAspect} reads to set the GUC per transaction. This way the explicit propagation done by
 * the application (e.g. async analysis pipeline, ADR 0028) uses exactly the same path as the
 * authenticated flow.
 */
@Component
public class TenantRlsContextAdapter implements TenantRlsContext {

    @Override
    public void set(UUID tenantId) {
        TenantContextHolder.set(tenantId);
    }

    @Override
    public void clear() {
        TenantContextHolder.clear();
    }
}

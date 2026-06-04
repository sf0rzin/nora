package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.TenantRlsContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapter de {@link TenantRlsContext} que delega para o {@link TenantContextHolder} — o mesmo
 * ThreadLocal que o {@code JwtAuthenticationFilter} popula no request e que o {@code
 * TenantRlsAspect} lê para setar o GUC por transação. Assim a propagação explícita feita pela
 * aplicação (ex.: pipeline de análise async, ADR 0028) usa exatamente o mesmo caminho do fluxo
 * autenticado.
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

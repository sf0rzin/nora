package br.com.nora.api.infrastructure.security;

import java.util.UUID;

/**
 * Holder de tenant atual da request (ThreadLocal). Populado pelo {@link JwtAuthenticationFilter}
 * apos auth bem-sucedida e limpado pelo {@code doFilter} no finally.
 *
 * <p>Consumidores principais:
 *
 * <ul>
 *   <li>{@code TenantRlsAspect} (RLS Postgres): le este holder pra setar {@code SET LOCAL
 *       nora.current_tenant_id} no inicio de cada transacao.
 *   <li>Codigo de aplicacao ja recebe tenantId via parametro explicito (padrao DDD); este holder
 *       NAO substitui esse passing — e infra-side, pra fechar o caminho do RLS.
 * </ul>
 */
public final class TenantContextHolder {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static void set(UUID tenantId) {
        if (tenantId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(tenantId);
        }
    }

    public static UUID get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}

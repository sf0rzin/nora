package br.com.nora.api.infrastructure.security;

import java.util.UUID;

/**
 * Holder for the request's current tenant (ThreadLocal). Populated by {@link
 * JwtAuthenticationFilter} after successful auth and cleared by {@code doFilter} in the finally.
 *
 * <p>Main consumers:
 *
 * <ul>
 *   <li>{@code TenantRlsAspect} (Postgres RLS): reads this holder to set {@code SET LOCAL
 *       nora.current_tenant_id} at the start of each transaction.
 *   <li>Application code already receives tenantId via an explicit parameter (DDD pattern); this
 *       holder does NOT replace that passing — it is infra-side, to close the RLS path.
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

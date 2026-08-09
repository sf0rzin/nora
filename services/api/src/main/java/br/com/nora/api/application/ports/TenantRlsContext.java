package br.com.nora.api.application.ports;

import java.util.UUID;

/**
 * Lets the application layer EXPLICITLY propagate the tenant to the RLS mechanism (the GUC {@code
 * nora.current_tenant_id} that the {@code TenantRlsAspect} applies per transaction) in code that
 * runs OUTSIDE an HTTP request thread — where the {@code TenantContextHolder} was not populated by
 * the authentication filter.
 *
 * <p>Use case (ADR 0028): the analysis pipeline runs async on an executor thread. Under RLS
 * enforce, its writes to enforced tables ({@code meeting_analyses} + children) need the GUC — but
 * the async thread does not inherit the {@code TenantContextHolder} from the request. The service,
 * which receives the {@code tenantId}, calls {@link #set(UUID)} at the start and {@link #clear()}
 * in the finally.
 *
 * <p>The adapter in infrastructure delegates to the same holder the aspect reads — keeping the DDD
 * rule (application does not know infrastructure) via a port.
 */
public interface TenantRlsContext {

    /** Binds the tenant to the current thread so the RLS aspect applies it in its transactions. */
    void set(UUID tenantId);

    /** Clears the tenant from the current thread. Call in the finally (pool threads are reused). */
    void clear();
}

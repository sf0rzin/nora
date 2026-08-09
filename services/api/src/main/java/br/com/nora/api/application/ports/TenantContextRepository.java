package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the tenant's commercial context (US14/US30). 1-1 with tenants. */
public interface TenantContextRepository {

    TenantContext save(TenantContext context);

    Optional<TenantContext> findByTenantId(UUID tenantId);
}

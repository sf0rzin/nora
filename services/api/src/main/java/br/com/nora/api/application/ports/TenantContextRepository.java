package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/** Persistencia do contexto comercial do tenant (US14/US30). 1-1 com tenants. */
public interface TenantContextRepository {

    TenantContext save(TenantContext context);

    Optional<TenantContext> findByTenantId(UUID tenantId);
}

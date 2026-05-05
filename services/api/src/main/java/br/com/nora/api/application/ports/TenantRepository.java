package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.Tenant;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    boolean existsBySlug(String slug);

    Tenant save(Tenant tenant);
}

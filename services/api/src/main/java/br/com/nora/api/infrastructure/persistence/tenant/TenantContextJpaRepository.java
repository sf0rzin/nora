package br.com.nora.api.infrastructure.persistence.tenant;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantContextJpaRepository extends JpaRepository<TenantContextJpaEntity, UUID> {

    Optional<TenantContextJpaEntity> findByTenantId(UUID tenantId);
}

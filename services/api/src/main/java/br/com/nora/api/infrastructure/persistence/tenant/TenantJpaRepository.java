package br.com.nora.api.infrastructure.persistence.tenant;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsBySlug(String slug);
}

package br.com.nora.api.infrastructure.persistence.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectJpaRepository extends JpaRepository<ProjectJpaEntity, UUID> {

    Optional<ProjectJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ProjectJpaEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}

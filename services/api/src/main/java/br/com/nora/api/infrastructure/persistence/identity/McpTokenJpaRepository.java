package br.com.nora.api.infrastructure.persistence.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface McpTokenJpaRepository extends JpaRepository<McpTokenJpaEntity, UUID> {

    Optional<McpTokenJpaEntity> findByTokenHash(String tokenHash);

    List<McpTokenJpaEntity> findAllByTenantIdAndUserIdOrderByCreatedAtDesc(
            UUID tenantId, UUID userId);

    Optional<McpTokenJpaEntity> findByIdAndTenantIdAndUserId(UUID id, UUID tenantId, UUID userId);
}

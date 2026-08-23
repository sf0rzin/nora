package br.com.nora.api.infrastructure.persistence.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {

    Optional<UserJpaEntity> findByEmail(String email);

    /**
     * The tenant's users by display name, one page at a time. Feeds the IAM directory ({@code GET
     * /iam/users}); the {@code Pageable} carries the ceiling, so there is no unbounded read here.
     */
    List<UserJpaEntity> findByTenantIdOrderByDisplayNameAsc(UUID tenantId, Pageable pageable);
}

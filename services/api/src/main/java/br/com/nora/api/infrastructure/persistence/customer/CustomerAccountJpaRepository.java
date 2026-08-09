package br.com.nora.api.infrastructure.persistence.customer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerAccountJpaRepository
        extends JpaRepository<CustomerAccountJpaEntity, UUID> {

    Optional<CustomerAccountJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Case-insensitive match by name within the tenant — mirrors the UNIQUE index {@code
     * idx_customer_accounts_tenant_name (tenant_id, LOWER(name))} used in the get-or-create.
     */
    @Query(
            "SELECT a FROM CustomerAccountJpaEntity a "
                    + "WHERE a.tenantId = :tenantId AND LOWER(a.name) = LOWER(:name)")
    Optional<CustomerAccountJpaEntity> findByTenantIdAndLowerName(
            @Param("tenantId") UUID tenantId, @Param("name") String name);
}

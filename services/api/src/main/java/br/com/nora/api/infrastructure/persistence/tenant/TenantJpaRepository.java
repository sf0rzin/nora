package br.com.nora.api.infrastructure.persistence.tenant;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TenantJpaRepository extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsBySlug(String slug);

    // @SQLRestriction(deleted_at IS NULL) on the entity filters soft-deleted rows automatically.
    @Query("SELECT t.id FROM TenantJpaEntity t")
    List<UUID> findAllActiveIds();
}

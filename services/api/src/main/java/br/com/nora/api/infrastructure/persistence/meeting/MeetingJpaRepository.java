package br.com.nora.api.infrastructure.persistence.meeting;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MeetingJpaRepository extends JpaRepository<MeetingJpaEntity, UUID> {

    Optional<MeetingJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query(
            "SELECT m FROM MeetingJpaEntity m WHERE m.tenantId = :tenantId ORDER BY m.createdAt DESC")
    Page<MeetingJpaEntity> findByTenantOrderByCreatedAtDesc(UUID tenantId, Pageable pageable);
}

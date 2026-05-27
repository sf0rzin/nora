package br.com.nora.api.infrastructure.persistence.meeting;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingJpaRepository extends JpaRepository<MeetingJpaEntity, UUID> {

    // Resolucao de N+1 fica no nivel da entidade via @BatchSize(participants, tags),
    // nao no repository. Tentar @EntityGraph(attributePaths={"participants","tags"})
    // dispara MultipleBagFetchException no Hibernate 6 porque participants e tags
    // sao List sem @OrderColumn (sao "bags") e Hibernate nao pode JOIN FETCH duas bags
    // simultaneamente. @BatchSize agrupa a carga em ~N+1/batchSize queries — bom
    // suficiente em listagens com paginacao tipica.

    Optional<MeetingJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    long countByTenantIdAndProcessingStatus(UUID tenantId, String processingStatus);

    long countByTenantIdAndCreatedAtGreaterThanEqual(UUID tenantId, OffsetDateTime createdAt);

    @Query(
            "SELECT m FROM MeetingJpaEntity m "
                    + "WHERE m.tenantId = :tenantId "
                    + "AND (cast(:search as string) IS NULL "
                    + "     OR LOWER(m.title) LIKE LOWER(CONCAT('%', cast(:search as string), '%'))) "
                    + "AND (cast(:status as string) IS NULL OR m.processingStatus = cast(:status as string)) "
                    + "AND (cast(:fromTs as timestamp) IS NULL OR m.createdAt >= :fromTs) "
                    + "AND (cast(:toTs as timestamp) IS NULL OR m.createdAt <= :toTs) "
                    + "ORDER BY m.createdAt DESC")
    Page<MeetingJpaEntity> search(
            @Param("tenantId") UUID tenantId,
            @Param("search") String search,
            @Param("status") String status,
            @Param("fromTs") OffsetDateTime fromTs,
            @Param("toTs") OffsetDateTime toTs,
            Pageable pageable);
}

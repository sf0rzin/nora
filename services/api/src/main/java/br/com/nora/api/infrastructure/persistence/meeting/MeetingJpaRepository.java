package br.com.nora.api.infrastructure.persistence.meeting;

import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingJpaRepository extends JpaRepository<MeetingJpaEntity, UUID> {

    // N+1 resolution lives at the entity level via @BatchSize(participants, tags),
    // not in the repository. Trying @EntityGraph(attributePaths={"participants","tags"})
    // throws MultipleBagFetchException on Hibernate 6 because participants and tags
    // are Lists without @OrderColumn (they are "bags") and Hibernate cannot JOIN FETCH two
    // bags at once. @BatchSize groups the load into ~N+1/batchSize queries — good
    // enough for listings with typical pagination.

    Optional<MeetingJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Same lookup, with {@code SELECT ... FOR UPDATE}. Used by reprocess: without the lock, two
     * concurrent calls read the status BEFORE each other's update, both get past the PROCESSING
     * guard and both schedule the analysis — two pipelines over the same meeting, doubled LLM
     * billing and duplicated external effects. The lock serializes the transactions on the row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MeetingJpaEntity m WHERE m.id = :id AND m.tenantId = :tenantId")
    Optional<MeetingJpaEntity> findByIdAndTenantIdForUpdate(
            @Param("id") UUID id, @Param("tenantId") UUID tenantId);

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

    // ---- Hard-delete (LGPD: right to be forgotten + retention, ADR 0021/0029) ----
    // Native query = raw SQL: IGNORES the entity's @SQLDelete (soft) and @SQLRestriction
    // (deleted_at IS NULL) — it is a real PHYSICAL DELETE. The FK ON DELETE CASCADE (V004)
    // propagates to transcripts (raw_text = PII at rest), participants, tags and meeting_analyses
    // (+ children).

    @Modifying
    @Query(
            value = "DELETE FROM meetings WHERE id = :id AND tenant_id = :tenantId",
            nativeQuery = true)
    int hardDeleteByIdAndTenant(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Modifying
    @Query(
            value = "DELETE FROM meetings WHERE tenant_id = :tenantId AND created_at < :cutoff",
            nativeQuery = true)
    int hardDeleteByTenantOlderThan(
            @Param("tenantId") UUID tenantId, @Param("cutoff") OffsetDateTime cutoff);
}

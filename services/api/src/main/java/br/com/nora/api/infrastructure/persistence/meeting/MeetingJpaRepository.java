package br.com.nora.api.infrastructure.persistence.meeting;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * How long a PENDING meeting may sit untouched before {@link #claimForReanalysis} stops reading
     * it as "queued" and lets a reprocess take it.
     *
     * <p>Wide enough that an analysis already on its way finishes well inside it, so the guard
     * against a double dispatch keeps working; narrow enough that one deploy does not leave a
     * meeting unusable for the rest of the day.
     *
     * <p>The column compared is updated_at, which every status transition rewrites: the domain
     * stamps it on each status change and the claim below sets it explicitly.
     */
    Duration STALE_PENDING_CLAIM_WINDOW = Duration.ofMinutes(15);

    /**
     * Atomically claims a meeting for re-analysis: moves it to PENDING and reports whether THIS
     * caller is the one that moved it.
     *
     * <p>Replaces a read-then-check-then-write that a row lock was supposed to make safe. It never
     * was. The caller reads the meeting once before the lock, to authorize on it, and that read
     * puts the entity in the persistence context; Hibernate then serves the {@code SELECT ... FOR
     * UPDATE} from that managed instance without refreshing its state. The lock was taken and the
     * blocking did happen, but the second transaction still saw the pre-lock status, so a double
     * click scheduled two pipelines anyway. The entity carries no {@code @Version} either, so
     * nothing turned the stale read into an error — it failed silently.
     *
     * <p>A conditional UPDATE has no such gap: the database matches the WHERE against the committed
     * row, so of two concurrent statements exactly one can find the meeting claimable. Row count 1
     * means "you moved it, dispatch"; 0 means it is already running, was queued moments ago, or is
     * gone.
     *
     * <p>Claimable is not only COMPLETED and FAILED. A PENDING row counts too once it has sat
     * untouched for {@link #STALE_PENDING_CLAIM_WINDOW}, because PENDING never proved a dispatch
     * was on its way: the executor queue lives in memory, so an ordinary restart drops every task
     * still sitting in it; auto-dispatch can be off by configuration; and the analysis bean can be
     * absent. Only the analysis itself writes a status afterwards and nothing sweeps abandoned
     * rows, so the meeting stayed queued forever with its re-analyse button disabled. Inside the
     * window the guard against a double dispatch is unchanged.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    "UPDATE meetings SET processing_status = 'PENDING', updated_at = now() "
                            + "WHERE id = :id AND tenant_id = :tenantId "
                            + "AND (processing_status IN ('COMPLETED', 'FAILED') "
                            + "     OR (processing_status = 'PENDING' "
                            + "         AND updated_at < :stalePendingBefore))",
            nativeQuery = true)
    int claimForReanalysis(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("stalePendingBefore") OffsetDateTime stalePendingBefore);

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

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

    // Resolucao de N+1 fica no nivel da entidade via @BatchSize(participants, tags),
    // nao no repository. Tentar @EntityGraph(attributePaths={"participants","tags"})
    // dispara MultipleBagFetchException no Hibernate 6 porque participants e tags
    // sao List sem @OrderColumn (sao "bags") e Hibernate nao pode JOIN FETCH duas bags
    // simultaneamente. @BatchSize agrupa a carga em ~N+1/batchSize queries — bom
    // suficiente em listagens com paginacao tipica.

    Optional<MeetingJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Mesma busca, com {@code SELECT ... FOR UPDATE}. Usada pelo reprocess: sem o lock, duas
     * chamadas concorrentes leem o status ANTES do update uma da outra, as duas passam pela guarda
     * de PROCESSING e as duas agendam a análise — dois pipelines sobre o mesmo meeting, cobrança de
     * LLM dobrada e efeitos externos duplicados. O lock serializa as transações na linha.
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

    // ---- Hard-delete (LGPD: direito ao esquecimento + retenção, ADR 0021/0029) ----
    // Native query = SQL cru: IGNORA o @SQLDelete (soft) e o @SQLRestriction (deleted_at IS NULL)
    // da entidade — é um DELETE FÍSICO de verdade. O FK ON DELETE CASCADE (V004) propaga pra
    // transcripts (raw_text = PII em repouso), participants, tags e meeting_analyses (+ filhos).

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

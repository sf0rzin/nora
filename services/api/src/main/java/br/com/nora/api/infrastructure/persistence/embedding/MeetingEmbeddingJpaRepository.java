package br.com.nora.api.infrastructure.persistence.embedding;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeetingEmbeddingJpaRepository
        extends JpaRepository<MeetingEmbeddingJpaEntity, UUID> {

    List<MeetingEmbeddingJpaEntity> findByTenantIdAndModel(UUID tenantId, String model);

    /**
     * Upsert by meeting_id (re-analysis overwrites). Under RLS enforce, tenant_id matches the GUC.
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO meeting_embeddings"
                            + " (meeting_id, tenant_id, model, dim, embedding, source_chars, created_at,"
                            + " updated_at) VALUES (:meetingId, :tenantId, :model, :dim, :embedding,"
                            + " :sourceChars, now(), now()) ON CONFLICT (meeting_id) DO UPDATE SET model"
                            + " = EXCLUDED.model, dim = EXCLUDED.dim, embedding = EXCLUDED.embedding,"
                            + " source_chars = EXCLUDED.source_chars, updated_at = now()",
            nativeQuery = true)
    void upsert(
            @Param("meetingId") UUID meetingId,
            @Param("tenantId") UUID tenantId,
            @Param("model") String model,
            @Param("dim") int dim,
            @Param("embedding") String embedding,
            @Param("sourceChars") int sourceChars);

    /**
     * Backfill candidates: a meeting carries an analysed summary (only AnalysisService writes
     * summary_snippet) and either has no vector or has one from another model, which the search
     * ignores just as completely. Newest first — the recent meetings are the ones a user searches
     * for. Under RLS enforce both tables are filtered by the GUC the caller set.
     */
    @Query(
            value =
                    "SELECT m.id, m.summary_snippet FROM meetings m LEFT JOIN meeting_embeddings e"
                            + " ON e.meeting_id = m.id WHERE m.tenant_id = :tenantId AND"
                            + " m.summary_snippet IS NOT NULL AND btrim(m.summary_snippet) <> ''"
                            + " AND (e.meeting_id IS NULL OR e.model <> :model) ORDER BY"
                            + " m.created_at DESC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findPendingIndex(
            @Param("tenantId") UUID tenantId,
            @Param("model") String model,
            @Param("limit") int limit);

    /** Same predicate as {@link #findPendingIndex}, counted without the ceiling. */
    @Query(
            value =
                    "SELECT COUNT(*) FROM meetings m LEFT JOIN meeting_embeddings e ON e.meeting_id"
                            + " = m.id WHERE m.tenant_id = :tenantId AND m.summary_snippet IS NOT"
                            + " NULL AND btrim(m.summary_snippet) <> '' AND (e.meeting_id IS NULL"
                            + " OR e.model <> :model)",
            nativeQuery = true)
    long countPendingIndex(@Param("tenantId") UUID tenantId, @Param("model") String model);
}

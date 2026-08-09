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
}

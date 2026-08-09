package br.com.nora.api.infrastructure.persistence.embedding;

import br.com.nora.api.application.ports.EmbeddingRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter for {@link EmbeddingRepository}. Serializes the vector as a JSON array of floats in TEXT
 * (see V021 — decision not to use pgvector at this scale). {@code @Transactional} so that, under
 * RLS enforce, the aspect applies the GUC on writes/reads of {@code meeting_embeddings} (enforced
 * table).
 */
@Repository
public class EmbeddingRepositoryAdapter implements EmbeddingRepository {

    private final MeetingEmbeddingJpaRepository jpa;
    private final ObjectMapper mapper;

    public EmbeddingRepositoryAdapter(MeetingEmbeddingJpaRepository jpa, ObjectMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void upsert(
            UUID meetingId, UUID tenantId, String modelId, float[] vector, int sourceChars) {
        jpa.upsert(meetingId, tenantId, modelId, vector.length, toJson(vector), sourceChars);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredEmbedding> findByTenantAndModel(UUID tenantId, String modelId) {
        return jpa.findByTenantIdAndModel(tenantId, modelId).stream()
                .map(e -> new StoredEmbedding(e.getMeetingId(), fromJson(e.getEmbedding())))
                .toList();
    }

    private String toJson(float[] vector) {
        try {
            return mapper.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("falha ao serializar embedding", e);
        }
    }

    private float[] fromJson(String json) {
        try {
            return mapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("falha ao desserializar embedding", e);
        }
    }
}

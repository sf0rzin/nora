package br.com.nora.api.application.ports;

import java.util.List;
import java.util.UUID;

/** Persistência dos embeddings de reunião (RAG). Escopado por tenant (RLS, ADR 0028). */
public interface EmbeddingRepository {

    /** Insere/atualiza o embedding de uma reunião. */
    void upsert(UUID meetingId, UUID tenantId, String modelId, float[] vector, int sourceChars);

    /** Todos os embeddings do tenant gerados pelo mesmo {@code modelId} (mesmo espaço vetorial). */
    List<StoredEmbedding> findByTenantAndModel(UUID tenantId, String modelId);

    record StoredEmbedding(UUID meetingId, float[] vector) {}
}

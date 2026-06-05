package br.com.nora.api.application.embedding;

import br.com.nora.api.application.ports.EmbeddingClient;
import br.com.nora.api.application.ports.EmbeddingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Indexação e busca semântica (RAG) das reuniões. O embedding é gerado a partir do RESUMO já
 * tratado (não da transcrição bruta) — LGPD/PII. A busca computa cosseno em Java sobre os vetores
 * do tenant (ver V021: sem pgvector nesta escala). Tudo best-effort: falha de embedding nunca
 * derruba o caller.
 */
@Service
public class EmbeddingService {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingClient client;
    private final EmbeddingRepository repo;

    public EmbeddingService(EmbeddingClient client, EmbeddingRepository repo) {
        this.client = client;
        this.repo = repo;
    }

    /**
     * Gera + guarda o embedding do texto da reunião. Falha = log + segue (não derruba a análise).
     */
    public void index(UUID meetingId, UUID tenantId, String text) {
        if (!client.isEnabled() || text == null || text.isBlank()) {
            return;
        }
        try {
            float[] v = client.embed(text);
            repo.upsert(meetingId, tenantId, client.modelId(), v, text.length());
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Falha ao indexar embedding meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
        }
    }

    /**
     * IDs das top-K reuniões do tenant mais similares à query. Vazio se desligado/sem dados/falha.
     */
    public List<UUID> search(UUID tenantId, String query, int k) {
        if (!client.isEnabled() || query == null || query.isBlank() || k <= 0) {
            return List.of();
        }
        final float[] q;
        try {
            q = client.embed(query);
        } catch (RuntimeException ex) {
            LOG.warn("Falha ao embeddar query tenantId={} cause={}", tenantId, ex.getMessage());
            return List.of();
        }
        return repo.findByTenantAndModel(tenantId, client.modelId()).stream()
                .map(s -> new Scored(s.meetingId(), cosine(q, s.vector())))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(k)
                .map(Scored::meetingId)
                .toList();
    }

    /** Similaridade do cosseno. 0 quando dimensões divergem ou algum vetor é nulo. */
    static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private record Scored(UUID meetingId, double score) {}
}

package br.com.nora.api.application.embedding;

import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.ports.EmbeddingClient;
import br.com.nora.api.application.ports.EmbeddingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Semantic indexing and search (RAG) of the meetings. The embedding is generated from the already
 * processed SUMMARY (not from the raw transcript) — LGPD/PII. The search computes cosine in Java
 * over the tenant's vectors (see V021: no pgvector at this scale). All best-effort: an embedding
 * failure never takes down the caller.
 *
 * <p>Every provider call made here is billed, so each one emits a usage event through
 * {@link UsageRecorder} (ADR 0024) — the same path the analysis uses, not a second report. The
 * {@code service} dimension separates ordinary product traffic from an operator-initiated backfill,
 * which is the one that can spend a lot at once.
 */
@Service
public class EmbeddingService {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingService.class);

    /** {@code usage_events.service} for indexing and searching on the product path. */
    public static final String USAGE_SERVICE = "embedding";

    /** {@code usage_events.service} for the operator backfill (EmbeddingBackfillService). */
    public static final String USAGE_SERVICE_BACKFILL = "embedding-backfill";

    private final EmbeddingClient client;
    private final EmbeddingRepository repo;
    private final UsageRecorder usage;

    public EmbeddingService(EmbeddingClient client, EmbeddingRepository repo, UsageRecorder usage) {
        this.client = client;
        this.repo = repo;
        this.usage = usage;
    }

    /**
     * Generates + stores the embedding of the meeting text. Failure = log + continue (does not take
     * down the analysis). Returns whether a vector was written, which is what the backfill counts.
     */
    public boolean index(UUID meetingId, UUID tenantId, String text) {
        return index(meetingId, tenantId, text, USAGE_SERVICE);
    }

    /**
     * Same as {@link #index(UUID, UUID, String)} with the usage dimension chosen by the caller.
     * Package-private: only the backfill in this package labels its calls differently.
     */
    boolean index(UUID meetingId, UUID tenantId, String text, String usageService) {
        if (!client.isEnabled() || text == null || text.isBlank()) {
            return false;
        }
        try {
            float[] v = embedBilled(usageService, tenantId, text);
            repo.upsert(meetingId, tenantId, client.modelId(), v, text.length());
            return true;
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Failed to index embedding meetingId={} tenantId={} cause={}",
                    meetingId,
                    tenantId,
                    ex.getMessage());
            return false;
        }
    }

    /**
     * IDs of the tenant's top-K meetings most similar to the query. Empty if off/no data/failure.
     */
    public List<UUID> search(UUID tenantId, String query, int k) {
        if (!client.isEnabled() || query == null || query.isBlank() || k <= 0) {
            return List.of();
        }
        final float[] q;
        try {
            q = embedBilled(USAGE_SERVICE, tenantId, query);
        } catch (RuntimeException ex) {
            LOG.warn("Failed to embed query tenantId={} cause={}", tenantId, ex.getMessage());
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

    /**
     * The one place that calls the provider: embeds, records the cost event either way, and
     * rethrows so each caller keeps its own best-effort handling.
     */
    private float[] embedBilled(String usageService, UUID tenantId, String text) {
        long startedAt = System.nanoTime();
        try {
            EmbeddingClient.Embedding result = client.embedWithUsage(text);
            recordUsage(usageService, tenantId, result.promptTokens(), startedAt, "ok");
            return result.vector();
        } catch (RuntimeException ex) {
            recordUsage(usageService, tenantId, 0, startedAt, "error");
            throw ex;
        }
    }

    /**
     * Emits the cost event. Never throws: telemetry must not be able to fail an indexing or a
     * search. {@link UsageRecorder} is already a no-op when the control plane is off.
     */
    private void recordUsage(
            String usageService, UUID tenantId, int promptTokens, long startedAt, String status) {
        try {
            String modelId = client.modelId();
            int sep = modelId.indexOf(':');
            String provider = sep > 0 ? modelId.substring(0, sep) : "unknown";
            String model = sep > 0 ? modelId.substring(sep + 1) : modelId;
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            usage.recordExternal(
                    usageService,
                    provider,
                    model,
                    tenantId,
                    promptTokens,
                    0,
                    null,
                    (int) Math.min(Integer.MAX_VALUE, elapsedMs),
                    status);
        } catch (RuntimeException ex) {
            LOG.debug("Embedding usage event dropped: {}", ex.getMessage());
        }
    }

    /** Cosine similarity. 0 when the dimensions diverge or some vector is null. */
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

package br.com.nora.api.application.embedding;

import br.com.nora.api.application.platform.PlatformAuditRepository;
import br.com.nora.api.application.platform.PlatformConflictException;
import br.com.nora.api.application.platform.PlatformUnavailableException;
import br.com.nora.api.application.platform.PlatformValidationException;
import br.com.nora.api.application.ports.EmbeddingClient;
import br.com.nora.api.application.ports.EmbeddingIndexStatusSource;
import br.com.nora.api.application.ports.EmbeddingRepository;
import br.com.nora.api.application.ports.EmbeddingRepository.PendingIndex;
import br.com.nora.api.application.ports.TenantRlsContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Reindexing path of the RAG index. Until this existed, a meeting analysed before an embedding
 * credential was configured — or while the provider was failing — stayed out of {@code
 * meeting_embeddings} permanently, invisible to {@code GET /meetings/search}, and the only remedy
 * was {@code POST /meetings/{id}/reprocess}, which re-runs the whole LLM analysis to obtain one
 * vector.
 *
 * <p>It does not need the model. The input is the summary snippet already persisted on the meeting,
 * which is exactly what the live path indexes (ADR 0012: the summary has been through the PII
 * Shield, the title has not). One embedding call per meeting, orders of magnitude cheaper than an
 * analysis.
 *
 * <p><b>Two shapes of the same defect, one query.</b> A meeting with no row and a meeting whose row
 * came from a different provider/model are equally invisible: the search only compares vectors from
 * the same space ({@code EmbeddingRepository.findByTenantAndModel}). Switching the embedding model
 * empties the index without emptying the table, and this is the mechanism that refills it.
 *
 * <p><b>Bounded on purpose.</b> Embedding calls are billed, so nothing here runs by itself: no
 * startup catch-up and no scheduled sweep. An operator asks for the preview, reads what a run would
 * do, and then asks for one tenant at a time with a per-run ceiling. A run also stops early on a
 * time budget or on repeated provider failures, instead of hammering a provider that is rate
 * limiting or down.
 *
 * <p><b>RLS.</b> The write goes through the primary datasource as {@code nora_app}, which is
 * NOBYPASSRLS, so the tenant GUC has to be set explicitly — the operator request thread never
 * carried one. Same mechanism the async analysis pipeline uses (ADR 0028).
 */
@Service
public class EmbeddingBackfillService {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingBackfillService.class);

    /** Meetings indexed by one run when the caller does not say. */
    public static final int DEFAULT_LIMIT = 25;

    /** Hard ceiling per run. A bigger catch-up is several runs, on purpose. */
    public static final int MAX_LIMIT = 100;

    /** Tenant rows returned by the preview. Beyond this the response says it was truncated. */
    static final int MAX_TENANT_ROWS = 200;

    /** A run stops here even with candidates left, so the HTTP call cannot hang on the provider. */
    static final Duration RUN_BUDGET = Duration.ofSeconds(60);

    /** Consecutive failures that abort a run — a rate limit or an outage, not one bad row. */
    static final int CONSECUTIVE_FAILURES_ABORT = 3;

    private final EmbeddingService embeddings;
    private final EmbeddingClient client;
    private final EmbeddingRepository repo;
    private final EmbeddingIndexStatusSource statusSource;
    private final TenantRlsContext rlsContext;
    private final ObjectProvider<PlatformAuditRepository> auditProvider;

    public EmbeddingBackfillService(
            EmbeddingService embeddings,
            EmbeddingClient client,
            EmbeddingRepository repo,
            EmbeddingIndexStatusSource statusSource,
            TenantRlsContext rlsContext,
            ObjectProvider<PlatformAuditRepository> auditProvider) {
        this.embeddings = embeddings;
        this.client = client;
        this.repo = repo;
        this.statusSource = statusSource;
        this.rlsContext = rlsContext;
        this.auditProvider = auditProvider;
    }

    /**
     * What a backfill would do, before any of it is done and before a cent is spent. Pure SQL: no
     * provider call, so this is safe to poll.
     */
    public IndexStatus preview() {
        String model = client.modelId();
        try {
            EmbeddingIndexStatusSource.Totals totals = statusSource.totals(model);
            List<EmbeddingIndexStatusSource.TenantIndexStatus> tenants =
                    statusSource.byTenant(model, MAX_TENANT_ROWS);
            return new IndexStatus(
                    client.isEnabled(),
                    model,
                    statusSource.source(),
                    DEFAULT_LIMIT,
                    MAX_LIMIT,
                    totals.analysedMeetings(),
                    totals.indexed(),
                    totals.missingVector(),
                    totals.staleModel(),
                    tenants,
                    tenants.size() >= MAX_TENANT_ROWS);
        } catch (DataAccessException ex) {
            throw new PlatformUnavailableException("primary database unavailable at runtime");
        }
    }

    /**
     * Indexes up to {@code limit} pending meetings of one tenant. Idempotent — a meeting that
     * already carries a vector from the current model is never a candidate, so running it twice
     * costs nothing the second time.
     */
    public RunOutcome run(UUID tenantId, Integer requestedLimit, String operator) {
        if (tenantId == null) {
            throw new PlatformValidationException("tenantId is required", false);
        }
        if (requestedLimit != null && requestedLimit < 1) {
            throw new PlatformValidationException("limit must be >= 1", false);
        }
        if (!client.isEnabled()) {
            // Loud rather than a 200 full of zeros: without a credential every call would be
            // skipped and the operator would read the empty result as "nothing to do".
            throw new PlatformConflictException(
                    "embeddings are disabled: no credential configured for the active provider");
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        String model = client.modelId();
        String usageService = EmbeddingService.USAGE_SERVICE_BACKFILL;
        rlsContext.set(tenantId);
        try {
            List<PendingIndex> pending = repo.findPendingIndex(tenantId, model, limit);
            long deadline = System.nanoTime() + RUN_BUDGET.toNanos();
            int attempted = 0;
            int indexed = 0;
            int failed = 0;
            int consecutiveFailures = 0;
            String stoppedReason = null;
            for (PendingIndex m : pending) {
                if (System.nanoTime() > deadline) {
                    stoppedReason = "run budget of " + RUN_BUDGET.toSeconds() + "s exhausted";
                    break;
                }
                attempted++;
                boolean written = embeddings.index(m.meetingId(), tenantId, m.text(), usageService);
                if (written) {
                    indexed++;
                    consecutiveFailures = 0;
                } else {
                    failed++;
                    consecutiveFailures++;
                    if (consecutiveFailures >= CONSECUTIVE_FAILURES_ABORT) {
                        stoppedReason = consecutiveFailures + " consecutive provider failures";
                        break;
                    }
                }
            }
            long remaining = repo.countPendingIndex(tenantId, model);
            LOG.info(
                    "Embedding backfill tenantId={} model={} attempted={} indexed={} failed={}"
                            + " remaining={} stopped={}",
                    tenantId,
                    model,
                    attempted,
                    indexed,
                    failed,
                    remaining,
                    stoppedReason == null ? "no" : stoppedReason);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("model", model);
            detail.put("limit", limit);
            detail.put("indexed", indexed);
            detail.put("failed", failed);
            detail.put("remaining", remaining);
            audit(operator, tenantId, detail);
            return new RunOutcome(
                    tenantId,
                    model,
                    limit,
                    pending.size(),
                    attempted,
                    indexed,
                    failed,
                    remaining,
                    stoppedReason);
        } catch (DataAccessException ex) {
            throw new PlatformUnavailableException("primary database unavailable at runtime");
        } finally {
            rlsContext.clear();
        }
    }

    /**
     * Operator audit trail (ADR 0023), best-effort like the catalog's: a control plane that is off
     * or degraded must not undo a backfill that already happened and already spent money.
     */
    private void audit(String operator, UUID tenantId, Map<String, Object> detail) {
        PlatformAuditRepository log = auditProvider.getIfAvailable();
        if (log == null) {
            return; // control plane off: there is no audit table to write to
        }
        try {
            log.record(operator, "embeddings.backfill", "tenant", tenantId.toString(), detail);
        } catch (RuntimeException ex) {
            LOG.warn("Embedding backfill audit not recorded: {}", ex.getMessage());
        }
    }

    /**
     * Preview payload. {@code enabled} false means no credential: the counters still tell the
     * operator what is waiting, and a run would be refused. {@code source} names the database role
     * that answered — under RLS enforce without the telemetry datasource the primary role sees
     * nothing and the counters would read zero for the wrong reason.
     */
    public record IndexStatus(
            boolean enabled,
            String model,
            String source,
            int defaultLimit,
            int maxLimit,
            long analysedMeetings,
            long indexed,
            long missingVector,
            long staleModel,
            List<EmbeddingIndexStatusSource.TenantIndexStatus> tenants,
            boolean tenantsTruncated) {}

    /**
     * Result of one run. {@code candidates} is what the query returned, {@code attempted} what the
     * loop reached before any early stop, and {@code remaining} is measured again after the run
     * rather than subtracted — so it is the truth, not an estimate. {@code stoppedReason} is null
     * when the run finished its batch.
     */
    public record RunOutcome(
            UUID tenantId,
            String model,
            int limit,
            int candidates,
            int attempted,
            int indexed,
            int failed,
            long remaining,
            String stoppedReason) {}
}

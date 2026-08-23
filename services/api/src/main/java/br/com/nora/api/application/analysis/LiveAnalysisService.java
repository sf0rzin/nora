package br.com.nora.api.application.analysis;

import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveHighlights;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Live analysis of a transcript chunk during an ongoing meeting.
 *
 * <p>It delegates to the worker and does not persist anything — but it DOES spend money, once per
 * chunk, and the desktop posts a chunk every few seconds for the length of a meeting. That made it
 * the largest untracked line in the product's AI bill: the worker has always reported {@code
 * tokensInput}/{@code tokensOutput} in {@code LiveAnalyzeMetadata} and nothing read them, so the
 * operator console (ADR 0024) showed a cost total that omitted an entire surface while reading like
 * the whole of it. A blind spot in a cost panel is worse than a missing panel, because it is
 * believed.
 */
@Service
public class LiveAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(LiveAnalysisService.class);

    /** {@code usage_events.service} for the live-analysis path, distinct from batch analysis. */
    public static final String USAGE_SERVICE = "live-analysis";

    private final NlpWorkerClient worker;
    private final UsageRecorder usage;

    public LiveAnalysisService(NlpWorkerClient worker, UsageRecorder usage) {
        this.worker = worker;
        this.usage = usage;
    }

    public WorkerDtos.LiveAnalyzeResponse analyze(
            UUID tenantId, String transcriptChunk, String language, LiveHighlights previous) {
        LOG.debug("live-analyze: tenant={} chunk={} chars", tenantId, transcriptChunk.length());
        long startedAt = System.nanoTime();
        try {
            WorkerDtos.LiveAnalyzeResponse response =
                    worker.analyzeLive(transcriptChunk, language, previous);
            recordUsage(tenantId, response.metadata(), startedAt, "ok");
            return response;
        } catch (RuntimeException ex) {
            // Recorded on failure too, with status=error: a tenant whose live analyses are all
            // failing is precisely what the console should be able to see, and a failed call can
            // still have been billed by the provider.
            recordUsage(tenantId, null, startedAt, "error");
            throw ex;
        }
    }

    /**
     * Emits the cost event. Never throws — telemetry must not be able to fail a live analysis,
     * which is running while someone is in a meeting. {@link UsageRecorder} is already a no-op when
     * the control plane is off.
     *
     * <p>The model is taken from the worker's own metadata rather than from configuration: the
     * worker decides which model answered, and a number attributed to the wrong model is a wrong
     * cost, not an approximate one. When the metadata is absent (the error path) the tokens are
     * zero and the model is reported as unknown, which is honest about what happened.
     */
    private void recordUsage(
            UUID tenantId, WorkerDtos.LiveMetadata metadata, long startedAt, String status) {
        try {
            String modelVersion = metadata == null ? null : metadata.modelVersion();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            usage.recordExternal(
                    USAGE_SERVICE,
                    providerOf(modelVersion),
                    modelOf(modelVersion),
                    tenantId,
                    // Every field of the worker's metadata is nullable on the wire, so each one
                    // is defaulted rather than unboxed: a missing token count means "not
                    // reported", and turning that into a NullPointerException inside telemetry
                    // would fail the live analysis over a number nobody was waiting for.
                    metadata == null || metadata.tokensInput() == null ? 0 : metadata.tokensInput(),
                    metadata == null || metadata.tokensOutput() == null
                            ? 0
                            : metadata.tokensOutput(),
                    null,
                    (int) Math.min(Integer.MAX_VALUE, elapsedMs),
                    status);
        } catch (RuntimeException ex) {
            LOG.debug("Live-analysis usage event dropped: {}", ex.getMessage());
        }
    }

    /**
     * The worker reports {@code modelVersion} as {@code provider-model} (e.g. {@code
     * openai-gpt-4o-mini}), the same shape the batch analysis path parses.
     */
    private static String providerOf(String modelVersion) {
        if (modelVersion == null || modelVersion.isBlank()) {
            return "unknown";
        }
        int sep = modelVersion.indexOf('-');
        return sep > 0 ? modelVersion.substring(0, sep) : modelVersion;
    }

    private static String modelOf(String modelVersion) {
        if (modelVersion == null || modelVersion.isBlank()) {
            return "unknown";
        }
        int sep = modelVersion.indexOf('-');
        return sep > 0 ? modelVersion.substring(sep + 1) : modelVersion;
    }
}

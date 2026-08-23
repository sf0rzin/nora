package br.com.nora.api.application.meeting;

import br.com.nora.api.application.platform.UsageRecorder;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Split preview of a .txt file with several concatenated meetings (split). It only orchestrates the
 * call to the {@code /split} worker and returns the proposed boundaries — it does NOT create a
 * meeting, it does NOT persist anything. The confirmation screen and the real slicing are
 * client-side.
 *
 * <p>Persisting nothing is not the same as costing nothing, and that distinction is why this class
 * emits usage. A split runs the LLM over the whole file in windows — up to about five of them for a
 * 1MB transcript — which makes it potentially the most expensive single request the product serves.
 * The worker has reported {@code tokensInput}/{@code tokensOutput} in {@code SplitMetadata} since
 * it existed and nothing read them, so this surface was invisible to the cost console (ADR 0024)
 * while the console's total read as the product's spend.
 */
@Service
public class TranscriptSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptSplitService.class);

    /** {@code usage_events.service} for the split path. */
    public static final String USAGE_SERVICE = "split";

    private final NlpWorkerClient worker;
    private final UsageRecorder usage;

    public TranscriptSplitService(NlpWorkerClient worker, UsageRecorder usage) {
        this.worker = worker;
        this.usage = usage;
    }

    /**
     * @param tenantId caller's tenant (JWT) — the attribution of the cost event below, and the log
     *     context. The preview itself does not touch tenant data.
     * @param transcript content of the .txt (already validated by the controller: format, size).
     * @param language ISO (e.g. "pt-BR"); null/blank falls back to the default.
     */
    public SplitDtos.SplitResponse preview(UUID tenantId, String transcript, String language) {
        if (transcript == null || transcript.isBlank()) {
            throw new MeetingException.EmptyTranscript();
        }
        if (transcript.length() > Transcript.MAX_CHAR_COUNT) {
            throw new MeetingException.TranscriptTooLarge(Transcript.MAX_CHAR_COUNT);
        }
        LOG.debug("split-preview: tenant={} transcript={} chars", tenantId, transcript.length());
        String lang = language == null || language.isBlank() ? "pt-BR" : language;
        long startedAt = System.nanoTime();
        try {
            SplitDtos.SplitResponse response = worker.split(transcript, lang);
            recordUsage(tenantId, response.metadata(), startedAt, "ok");
            return response;
        } catch (RuntimeException ex) {
            // Also recorded on failure: a split that timed out halfway has already burned the
            // windows it did complete, and a tenant whose splits all fail is what the console
            // exists to surface.
            recordUsage(tenantId, null, startedAt, "error");
            throw ex;
        }
    }

    /**
     * Emits the cost event. Never throws: a telemetry failure must not take down a preview the user
     * is waiting on. {@link UsageRecorder} is already a no-op when the control plane is off.
     *
     * <p>Provider and model come from the worker's {@code modelVersion} ({@code provider-model}),
     * the same shape the analysis path parses, so the split rows land beside the others in the
     * console instead of under a name only this class uses.
     */
    private void recordUsage(
            UUID tenantId, SplitDtos.SplitMetadata metadata, long startedAt, String status) {
        try {
            String modelVersion = metadata == null ? null : metadata.modelVersion();
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            usage.recordExternal(
                    USAGE_SERVICE,
                    providerOf(modelVersion),
                    modelOf(modelVersion),
                    tenantId,
                    // Every metadata field is nullable on the wire; a missing count means "not
                    // reported" and must never become a NullPointerException inside telemetry.
                    metadata == null || metadata.tokensInput() == null ? 0 : metadata.tokensInput(),
                    metadata == null || metadata.tokensOutput() == null
                            ? 0
                            : metadata.tokensOutput(),
                    null,
                    (int) Math.min(Integer.MAX_VALUE, elapsedMs),
                    status);
        } catch (RuntimeException ex) {
            LOG.debug("Split usage event dropped: {}", ex.getMessage());
        }
    }

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

package br.com.nora.api.application.platform;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Emission port for AI usage telemetry. AnalysisService depends on it (in-process, the analysis
 * path); the /internal/platform/usage controller depends on it (the chat/external path). There is
 * always an active bean: the real implementation (gated on {@code nora.platform.enabled=true}) or a
 * no-op (when the platform is disabled) — so AnalysisService never breaks for lack of a bean.
 */
public interface UsageRecorder {

    /**
     * Emits usage from the ANALYSIS path (in-process in the API). {@code modelVersion} is what the
     * worker reported in the metadata (e.g. {@code "openai-gpt-4o-mini"}); the provider/model is
     * derived from it. Never throws — a failure here must not bring down the analysis pipeline.
     */
    void recordAnalysisUsage(
            UUID tenantId,
            String modelVersion,
            int promptTokens,
            int completionTokens,
            Integer latencyMs,
            boolean stub);

    /**
     * Emits usage from an external caller (chat BFF, future multimodal) via POST
     * /internal/platform/usage. {@code costUsdHint} is best-effort; the authoritative cost is
     * recomputed from the catalog when the model is known. Never throws (fire-and-forget).
     */
    void recordExternal(
            String service,
            String provider,
            String model,
            UUID tenantId,
            int promptTokens,
            int completionTokens,
            BigDecimal costUsdHint,
            Integer latencyMs,
            String status);
}

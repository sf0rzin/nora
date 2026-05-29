package br.com.nora.api.application.platform;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Porta de emissão de telemetria de uso de IA. AnalysisService depende dela (in-process, caminho da
 * análise); o controller /internal/platform/usage depende dela (caminho do chat/externos). Há
 * sempre um bean ativo: a implementação real (gated {@code nora.platform.enabled=true}) ou um no-op
 * (quando a plataforma está desabilitada) — então AnalysisService nunca quebra por ausência de
 * bean.
 */
public interface UsageRecorder {

    /**
     * Emite usage do caminho da ANÁLISE (in-process na API). {@code modelVersion} é o que o worker
     * reportou no metadata (ex.: {@code "openai-gpt-4o-mini"}); o provider/model é derivado dele.
     * Nunca lança — falha aqui não pode derrubar o pipeline de análise.
     */
    void recordAnalysisUsage(
            UUID tenantId,
            String modelVersion,
            int promptTokens,
            int completionTokens,
            Integer latencyMs,
            boolean stub);

    /**
     * Emite usage de um caller externo (BFF de chat, multimodal futuro) via POST
     * /internal/platform/usage. {@code costUsdHint} é best-effort; o custo autoritativo é
     * recalculado do catálogo quando o modelo é conhecido. Nunca lança (fire-and-forget).
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

package br.com.nora.api.infrastructure.platform;

import br.com.nora.api.application.platform.UsageRecorder;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Recorder no-op ativo quando o control plane está desabilitado ({@code nora.platform.enabled} ausente
 * ou false — local/test/CI). Garante que AnalysisService e o controller /internal/usage sempre tenham
 * um bean {@link UsageRecorder}, sem depender do módulo platform. Mutuamente exclusivo com {@code
 * UsageTelemetryService} (ativo quando enabled=true).
 */
@Component
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpUsageRecorder implements UsageRecorder {

    @Override
    public void recordAnalysisUsage(
            UUID tenantId,
            String modelVersion,
            int promptTokens,
            int completionTokens,
            Integer latencyMs,
            boolean stub) {
        // no-op
    }

    @Override
    public void recordExternal(
            String service,
            String provider,
            String model,
            UUID tenantId,
            int promptTokens,
            int completionTokens,
            BigDecimal costUsdHint,
            Integer latencyMs,
            String status) {
        // no-op
    }
}

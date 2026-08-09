package br.com.nora.api.infrastructure.platform;

import br.com.nora.api.application.platform.UsageRecorder;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op recorder active when the control plane is disabled ({@code nora.platform.enabled} absent or
 * false — local/test/CI). Guarantees that AnalysisService and the /internal/usage controller always
 * have a {@link UsageRecorder} bean, without depending on the platform module. Mutually exclusive
 * with {@code UsageTelemetryService} (active when enabled=true).
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

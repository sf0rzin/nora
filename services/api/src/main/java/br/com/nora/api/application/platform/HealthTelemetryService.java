package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.HealthSnapshot;
import org.springframework.stereotype.Service;

/**
 * Health telemetry (track (b), ADR 0024). Delegates to {@link HealthMetricsSource} (today
 * Prometheus, ADR 0034). Does not depend on the platform database — it works even in degraded mode;
 * the source returns {@code unavailable} when not configured.
 */
@Service
public class HealthTelemetryService {

    private final HealthMetricsSource source;

    public HealthTelemetryService(HealthMetricsSource source) {
        this.source = source;
    }

    public HealthSnapshot health() {
        return source.fetch();
    }
}

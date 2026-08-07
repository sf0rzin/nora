package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.HealthSnapshot;
import org.springframework.stereotype.Service;

/**
 * Telemetria de saúde (frente (b), ADR 0024). Delega ao {@link HealthMetricsSource} (hoje
 * Prometheus, ADR 0034). Não depende do banco de plataforma — funciona mesmo em modo degradado; a
 * fonte devolve {@code unavailable} quando não configurada.
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

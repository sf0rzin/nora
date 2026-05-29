package br.com.nora.api.domain.platform;

import java.util.List;

/**
 * Snapshot de saúde do sistema lido do Application Insights (telemetria frente (b), ADR 0024). Se a
 * fonte não estiver configurada/disponível, {@code source="unavailable"} e {@code services} vazio —
 * o endpoint ainda responde 200 (soft) para a UI não quebrar.
 */
public record HealthSnapshot(
        String window, String source, List<ServiceHealth> services, boolean degraded, String note) {

    public record ServiceHealth(
            String role, long requests, long failed, double failureRate, Double p95LatencyMs) {}

    public static HealthSnapshot unavailable(String window, String note) {
        return new HealthSnapshot(window, "unavailable", List.of(), false, note);
    }
}

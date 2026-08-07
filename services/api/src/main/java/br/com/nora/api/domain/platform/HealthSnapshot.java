package br.com.nora.api.domain.platform;

import java.util.List;

/**
 * Snapshot de saúde do sistema (telemetria frente (b), ADR 0024). O campo {@code source} nomeia o
 * adaptador que respondeu — {@code "prometheus"} desde o ADR 0034, antes {@code
 * "application-insights"}. Se a fonte não estiver configurada/disponível, {@code
 * source="unavailable"} e {@code services} vazio — o endpoint ainda responde 200 (soft) para a UI
 * não quebrar (é o único valor de {@code source} em que a UI ramifica).
 */
public record HealthSnapshot(
        String window, String source, List<ServiceHealth> services, boolean degraded, String note) {

    public record ServiceHealth(
            String role, long requests, long failed, double failureRate, Double p95LatencyMs) {}

    public static HealthSnapshot unavailable(String window, String note) {
        return new HealthSnapshot(window, "unavailable", List.of(), false, note);
    }
}

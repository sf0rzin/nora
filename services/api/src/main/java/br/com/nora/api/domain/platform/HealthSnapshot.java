package br.com.nora.api.domain.platform;

import java.util.List;

/**
 * System health snapshot (telemetry workstream (b), ADR 0024). The {@code source} field names the
 * adapter that responded — {@code "prometheus"} since ADR 0034, previously {@code
 * "application-insights"}. If the source is not configured/available, {@code source="unavailable"}
 * and {@code services} empty — the endpoint still responds 200 (soft) so the UI does not break (it
 * is the only {@code source} value the UI branches on).
 */
public record HealthSnapshot(
        String window, String source, List<ServiceHealth> services, boolean degraded, String note) {

    public record ServiceHealth(
            String role, long requests, long failed, double failureRate, Double p95LatencyMs) {}

    public static HealthSnapshot unavailable(String window, String note) {
        return new HealthSnapshot(window, "unavailable", List.of(), false, note);
    }
}

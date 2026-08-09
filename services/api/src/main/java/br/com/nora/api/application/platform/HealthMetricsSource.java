package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.HealthSnapshot;

/**
 * Read port for system health (telemetry (b), ADR 0024).
 *
 * <p>Backend-agnostic on purpose: the adapter in production is {@code PrometheusHealthSource} (ADR
 * 0034); before that it was the App Insights REST query API. Swapping the source must not change
 * the contract — {@link HealthSnapshot} is the same record for any adapter.
 */
public interface HealthMetricsSource {

    /** Never throws: on failure/unavailability it returns {@link HealthSnapshot#unavailable}. */
    HealthSnapshot fetch();
}

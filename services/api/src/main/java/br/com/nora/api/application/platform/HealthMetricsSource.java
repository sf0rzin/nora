package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.HealthSnapshot;

/** Porta de leitura de saúde do sistema (Application Insights, telemetria (b), ADR 0024). */
public interface HealthMetricsSource {

    /** Nunca lança: em falha/indisponibilidade devolve {@link HealthSnapshot#unavailable}. */
    HealthSnapshot fetch();
}

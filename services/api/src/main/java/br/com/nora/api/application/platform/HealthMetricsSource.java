package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.HealthSnapshot;

/**
 * Porta de leitura de saúde do sistema (telemetria (b), ADR 0024).
 *
 * <p>Agnóstica de backend de propósito: o adaptador em produção é o {@code PrometheusHealthSource}
 * (ADR 0034); antes era o App Insights REST query API. Trocar a fonte não pode mudar o contrato —
 * {@link HealthSnapshot} é o mesmo record para qualquer adaptador.
 */
public interface HealthMetricsSource {

    /** Nunca lança: em falha/indisponibilidade devolve {@link HealthSnapshot#unavailable}. */
    HealthSnapshot fetch();
}

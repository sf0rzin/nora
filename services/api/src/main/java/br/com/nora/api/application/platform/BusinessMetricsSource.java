package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.BusinessSnapshot;
import java.time.OffsetDateTime;

/**
 * Porta de leitura de métricas de negócio cross-tenant (telemetria (c), CORTÁVEL, ADR 0024). Read
 * operador-only do banco primário, sem contexto de tenant.
 */
public interface BusinessMetricsSource {

    BusinessSnapshot fetch(OffsetDateTime from, OffsetDateTime to);
}

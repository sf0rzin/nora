package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.BusinessSnapshot;
import java.time.OffsetDateTime;

/**
 * Read port for cross-tenant business metrics (telemetry (c), CUTTABLE, ADR 0024). Operator-only
 * read from the primary database, without tenant context.
 */
public interface BusinessMetricsSource {

    BusinessSnapshot fetch(OffsetDateTime from, OffsetDateTime to);
}

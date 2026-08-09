package br.com.nora.api.domain.platform;

import java.time.OffsetDateTime;

/**
 * Cross-tenant aggregated business metrics (telemetry workstream (c), CUTTABLE, ADR 0024).
 * Operator-only read from the primary database, with no tenant context. {@code enabled=false} when
 * the workstream is disabled/cut. {@code from}/{@code to} echo the queried window (mirrors
 * CostReport). {@code productivityAvg}/{@code customerConfidenceAvg} may be null in v1 (not
 * computed yet).
 */
public record BusinessSnapshot(
        OffsetDateTime from,
        OffsetDateTime to,
        boolean enabled,
        long analyses,
        long tenantsActive,
        Double productivityAvg,
        Double customerConfidenceAvg) {

    public static BusinessSnapshot disabled(OffsetDateTime from, OffsetDateTime to) {
        return new BusinessSnapshot(from, to, false, 0, 0, null, null);
    }
}

package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Aggregated AI cost report (GET /admin/platform/telemetry/cost). Aggregates usage_events by tenant
 * | model | service over a time window. Cost of {@code status=stub} events does not count.
 */
public record CostReport(
        OffsetDateTime from, OffsetDateTime to, String groupBy, List<Bucket> rows, Totals totals) {

    public record Bucket(
            String key,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd,
            long events) {}

    public record Totals(
            long promptTokens, long completionTokens, BigDecimal costUsd, long events) {}
}

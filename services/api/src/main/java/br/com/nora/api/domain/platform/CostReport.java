package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Relatório agregado de custo de IA (GET /admin/platform/telemetry/cost). Agrega usage_events por
 * tenant | model | service numa janela de tempo. Custo de eventos {@code status=stub} não conta.
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

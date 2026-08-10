package br.com.nora.api.infrastructure.platform.persistence;

import br.com.nora.api.application.platform.UsageEventRepository;
import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.domain.platform.UsageEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate adapter for AI usage events (usage_events table, ADR 0024). */
@Repository
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class JdbcUsageEventRepository implements UsageEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUsageEventRepository(NamedParameterJdbcTemplate platformJdbcTemplate) {
        this.jdbc = platformJdbcTemplate;
    }

    @Override
    public void insert(UsageEvent e) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("service", e.service())
                        .addValue("provider", e.provider())
                        .addValue("model", e.model())
                        .addValue("tenantId", e.tenantId())
                        .addValue("in", e.promptTokens())
                        .addValue("out", e.completionTokens())
                        .addValue("cost", e.costUsd())
                        .addValue("latency", e.latencyMs())
                        .addValue("status", e.status());
        jdbc.update(
                "INSERT INTO usage_events (service, provider, model, tenant_id, prompt_tokens,"
                        + " completion_tokens, cost_usd, latency_ms, status) VALUES (:service, :provider,"
                        + " :model, :tenantId, :in, :out, :cost, :latency, :status)",
                params);
    }

    @Override
    public CostReport aggregate(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        // groupBy is whitelisted in the service layer (tenant|model|service). Mapped here to
        // fixed expressions — no interpolation of user input.
        String keyExpr =
                switch (groupBy) {
                    case "tenant" -> "COALESCE(tenant_id::text, '(no tenant)')";
                    case "service" -> "service";
                    default -> "model";
                };
        String sql =
                "SELECT "
                        + keyExpr
                        + " AS k, COALESCE(SUM(prompt_tokens),0) AS pin, COALESCE(SUM(completion_tokens),0)"
                        + " AS pout, COALESCE(SUM(cost_usd),0) AS cost, COUNT(*) AS events FROM"
                        + " usage_events WHERE occurred_at >= :from AND occurred_at < :to AND status <>"
                        + " 'stub' GROUP BY "
                        + keyExpr
                        + " ORDER BY cost DESC";
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("from", from).addValue("to", to);
        List<CostReport.Bucket> rows =
                jdbc.query(
                        sql,
                        params,
                        (rs, n) ->
                                new CostReport.Bucket(
                                        rs.getString("k"),
                                        rs.getLong("pin"),
                                        rs.getLong("pout"),
                                        rs.getBigDecimal("cost"),
                                        rs.getLong("events")));
        long pin = 0;
        long pout = 0;
        long events = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (CostReport.Bucket b : rows) {
            pin += b.promptTokens();
            pout += b.completionTokens();
            events += b.events();
            cost = cost.add(b.costUsd() == null ? BigDecimal.ZERO : b.costUsd());
        }
        return new CostReport(
                from, to, groupBy, rows, new CostReport.Totals(pin, pout, cost, events));
    }
}

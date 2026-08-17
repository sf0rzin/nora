package br.com.nora.api.application.platform;

import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.domain.platform.UsageEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/** Persistence/aggregation port for AI usage events (usage_events table, ADR 0024). */
public interface UsageEventRepository {

    void insert(UsageEvent event);

    /** Aggregates cost by {@code groupBy} ∈ tenant|model|service in the window [from, to). */
    CostReport aggregate(OffsetDateTime from, OffsetDateTime to, String groupBy);

    /**
     * One tenant's own consumption, per bucket and per service (US33). {@code unit} and {@code
     * zone} are the reporting granularity and zone of the panel asking, so the buckets line up with
     * the ones the primary database produced for the same range — hence the {@code Granularity} of
     * the trends port rather than a second enum meaning the same thing.
     *
     * <p>Deliberately NOT the same query as {@link #aggregate}, which is the operator's view
     * (US83): that one carries no tenant predicate at all — it groups the whole platform and lets
     * the caller pick the dimension. Reusing it for a tenant-facing screen would mean filtering a
     * cross-tenant result in Java, which is one forgotten line away from serving another tenant's
     * numbers. Here the tenant is a bind in the WHERE clause and no code path can omit it.
     *
     * <p>{@code tenant_id} in this table is a telemetry dimension and not a security boundary (ADR
     * 0022 §6): the platform database has no RLS, so this predicate is the whole isolation.
     */
    List<TenantUsageRow> tenantSeries(
            UUID tenantId, OffsetDateTime from, OffsetDateTime to, Granularity unit, ZoneId zone);

    /**
     * One (bucket, service) cell of a tenant's consumption. {@code bucketStart} is a LOCAL date in
     * the reporting zone, and {@code calls} counts events — for {@code stt} an event is a session
     * issued and not a minute transcribed, so its tokens and cost are structurally zero (ADR 0045).
     */
    record TenantUsageRow(
            LocalDate bucketStart,
            String service,
            long calls,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {}
}

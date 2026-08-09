package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * AI usage event (usage_events table, ADR 0024). {@code tenantId} is a telemetry dimension (no FK —
 * the tenants table lives in the primary database). On inserts, {@code id}/{@code occurredAt} may
 * arrive null (database defaults). {@code status}: ok | error | stub | fallback.
 */
public record UsageEvent(
        UUID id,
        OffsetDateTime occurredAt,
        String service,
        String provider,
        String model,
        UUID tenantId,
        int promptTokens,
        int completionTokens,
        BigDecimal costUsd,
        Integer latencyMs,
        String status) {}

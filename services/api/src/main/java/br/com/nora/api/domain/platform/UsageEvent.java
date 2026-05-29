package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Evento de uso de IA (tabela usage_events, ADR 0024). {@code tenantId} é dimensão de telemetria
 * (sem FK — a tabela tenants vive no banco primário). Em inserts, {@code id}/{@code occurredAt}
 * podem vir null (defaults do banco). {@code status}: ok | error | stub | fallback.
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

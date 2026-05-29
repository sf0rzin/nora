package br.com.nora.api.domain.platform;

import java.time.OffsetDateTime;

/**
 * Métricas de negócio agregadas cross-tenant (telemetria frente (c), CORTÁVEL, ADR 0024). Leitura
 * operador-only do banco primário, sem contexto de tenant. {@code enabled=false} quando a frente
 * estiver desligada/cortada. {@code from}/{@code to} ecoam a janela consultada (espelha CostReport).
 * {@code productivityAvg}/{@code customerConfidenceAvg} podem ser null no v1 (não calculados ainda).
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

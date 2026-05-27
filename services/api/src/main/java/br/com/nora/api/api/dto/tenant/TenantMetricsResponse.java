package br.com.nora.api.api.dto.tenant;

/**
 * Resposta de GET /tenant/metrics (US33): visao rapida de atividade do tenant. Todos os contadores
 * sao escopados ao tenant do principal autenticado (nunca client-supplied).
 */
public record TenantMetricsResponse(
        long totalMeetings,
        long meetingsThisMonth,
        long completed,
        long processing,
        long pending,
        long failed,
        long totalActionItems,
        long openActionItems) {}

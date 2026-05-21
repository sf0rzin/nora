package br.com.nora.api.domain.customer;

/**
 * Tendencia da confianca do cliente comparada a ultima reuniao da mesma conta (ADR 0015). Null
 * (ausente) quando e a primeira reuniao da conta.
 */
public enum ConfidenceTrend {
    IMPROVING,
    STABLE,
    DECLINING
}

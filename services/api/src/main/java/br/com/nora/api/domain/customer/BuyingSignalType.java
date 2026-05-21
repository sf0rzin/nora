package br.com.nora.api.domain.customer;

/**
 * Tipo de sinal de compra detectado na reuniao (ADR 0015). Espelha o enum {@code
 * buyingSignals.type} de {@code meeting-analysis-v1.schema.json}.
 */
public enum BuyingSignalType {
    BUDGET_DISCUSSED,
    TIMELINE_DISCUSSED,
    STAKEHOLDER_INVOLVED,
    NEXT_STEP_REQUESTED,
    REFERENCE_REQUESTED,
    PROPOSAL_REQUESTED,
    OTHER
}

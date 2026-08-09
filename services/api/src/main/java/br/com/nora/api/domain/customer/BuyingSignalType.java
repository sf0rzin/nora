package br.com.nora.api.domain.customer;

/**
 * Type of buying signal detected in the meeting (ADR 0015). Mirrors the {@code buyingSignals.type}
 * enum of {@code meeting-analysis-v1.schema.json}.
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

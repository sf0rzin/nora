package br.com.nora.api.domain.customer;

/**
 * Type of objection raised by the customer in the meeting (ADR 0015). Mirrors the {@code
 * objections.type} enum of {@code meeting-analysis-v1.schema.json}.
 */
public enum ObjectionType {
    PRICE,
    TIMELINE,
    AUTHORITY,
    NEED,
    COMPETITOR_MENTION,
    TRUST,
    FEATURE_GAP,
    OTHER
}

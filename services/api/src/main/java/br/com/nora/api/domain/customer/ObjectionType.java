package br.com.nora.api.domain.customer;

/**
 * Tipo de objecao levantada pelo cliente na reuniao (ADR 0015). Espelha o enum {@code
 * objections.type} de {@code meeting-analysis-v1.schema.json}.
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

package br.com.nora.api.domain.customer;

/**
 * Band derived from the Customer Confidence Score (ADR 0015). Tunable in the worker, not in the
 * domain.
 */
public enum ConfidenceBand {
    LOW,
    MEDIUM,
    HIGH
}

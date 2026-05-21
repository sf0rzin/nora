package br.com.nora.api.domain.customer;

/** Banda derivada do Customer Confidence Score (ADR 0015). Tunavel no worker, nao no dominio. */
public enum ConfidenceBand {
    LOW,
    MEDIUM,
    HIGH
}

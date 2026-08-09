package br.com.nora.api.domain.meeting;

/** State of a meeting's processing pipeline. */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

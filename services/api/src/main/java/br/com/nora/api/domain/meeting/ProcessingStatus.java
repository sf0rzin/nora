package br.com.nora.api.domain.meeting;

/** Estado do pipeline de processamento de uma reuniao. */
public enum ProcessingStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

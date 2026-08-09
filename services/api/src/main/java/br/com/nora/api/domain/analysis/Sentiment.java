package br.com.nora.api.domain.analysis;

/** Overall sentiment of the meeting. Mirrors the enum in the meeting-analysis-v1 schema. */
public enum Sentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE,
    MIXED
}

package br.com.nora.api.domain.customer;

/**
 * Customer confidence trend compared to the last meeting of the same account (ADR 0015). Null
 * (absent) when it is the account's first meeting.
 */
public enum ConfidenceTrend {
    IMPROVING,
    STABLE,
    DECLINING
}

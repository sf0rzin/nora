package br.com.nora.api.api.dto.meeting;

import java.util.List;

/**
 * Persisted Customer Confidence (ADR 0015), returned in the meeting detail. Present only for
 * external meetings (conversation with a customer/lead); null for internal meetings.
 *
 * <p>{@code trend} is the authoritative value computed server-side (null on the account's first
 * meeting). {@code accountName} is the name of the account resolved via get-or-create.
 */
public record CustomerConfidenceResponse(
        int score,
        String band,
        String trend,
        String accountName,
        String rationale,
        List<BuyingSignalResponse> buyingSignals,
        List<ObjectionResponse> objections) {

    /** Detected buying signal (ADR 0015). */
    public record BuyingSignalResponse(String type, String quote, Double weight) {}

    /** Objection raised by the customer (ADR 0015). */
    public record ObjectionResponse(
            String type, String quote, String severity, String competitor) {}
}

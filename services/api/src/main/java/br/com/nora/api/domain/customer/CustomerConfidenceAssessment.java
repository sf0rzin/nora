package br.com.nora.api.domain.customer;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Avaliacao de confianca do cliente gerada pelo worker (ADR 0015). 1-1 por par (meeting, account).
 * Imutavel.
 *
 * <p>Enterprise-only: derivada de sentimento, sinais de compra e objecoes. Complementa (nao
 * substitui) o Account Health Score temporal. So existe quando o worker emite o bloco {@code
 * customerConfidence} (ainda nao emitido em 2026-05-21).
 */
public final class CustomerConfidenceAssessment {

    private static final int SCORE_MIN = 0;
    private static final int SCORE_MAX = 100;
    private static final int RATIONALE_MIN = 10;
    private static final int RATIONALE_MAX = 1000;

    private final UUID id;
    private final UUID tenantId;
    private final UUID meetingId;
    private final UUID customerAccountId;
    private final int score;
    private final ConfidenceBand band;
    private final ConfidenceTrend trend;
    private final List<BuyingSignal> buyingSignals;
    private final List<Objection> objections;
    private final String rationale;
    private final OffsetDateTime createdAt;

    public CustomerConfidenceAssessment(
            UUID id,
            UUID tenantId,
            UUID meetingId,
            UUID customerAccountId,
            int score,
            ConfidenceBand band,
            ConfidenceTrend trend,
            List<BuyingSignal> buyingSignals,
            List<Objection> objections,
            String rationale,
            OffsetDateTime createdAt) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
        if (customerAccountId == null) {
            throw new IllegalArgumentException("customerAccountId is required");
        }
        if (score < SCORE_MIN || score > SCORE_MAX) {
            throw new IllegalArgumentException(
                    "score must be between " + SCORE_MIN + " and " + SCORE_MAX);
        }
        if (band == null) {
            throw new IllegalArgumentException("band is required");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale is required");
        }
        String trimmedRationale = rationale.trim();
        if (trimmedRationale.length() < RATIONALE_MIN
                || trimmedRationale.length() > RATIONALE_MAX) {
            throw new IllegalArgumentException(
                    "rationale length must be between " + RATIONALE_MIN + " and " + RATIONALE_MAX);
        }
        this.id = id;
        this.tenantId = tenantId;
        this.meetingId = meetingId;
        this.customerAccountId = customerAccountId;
        this.score = score;
        this.band = band;
        this.trend = trend;
        this.buyingSignals =
                buyingSignals == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(buyingSignals));
        this.objections =
                objections == null
                        ? List.of()
                        : Collections.unmodifiableList(new ArrayList<>(objections));
        this.rationale = trimmedRationale;
        this.createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
    }

    /** Factory para criar uma avaliacao nova (id gerado, createdAt = now). */
    public static CustomerConfidenceAssessment newAssessment(
            UUID tenantId,
            UUID meetingId,
            UUID customerAccountId,
            int score,
            ConfidenceBand band,
            ConfidenceTrend trend,
            List<BuyingSignal> buyingSignals,
            List<Objection> objections,
            String rationale) {
        return new CustomerConfidenceAssessment(
                UUID.randomUUID(),
                tenantId,
                meetingId,
                customerAccountId,
                score,
                band,
                trend,
                buyingSignals,
                objections,
                rationale,
                OffsetDateTime.now());
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public UUID customerAccountId() {
        return customerAccountId;
    }

    public int score() {
        return score;
    }

    public ConfidenceBand band() {
        return band;
    }

    public ConfidenceTrend trend() {
        return trend;
    }

    public List<BuyingSignal> buyingSignals() {
        return buyingSignals;
    }

    public List<Objection> objections() {
        return objections;
    }

    public String rationale() {
        return rationale;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }
}

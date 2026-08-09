package br.com.nora.api.api.dto.meeting;

import java.util.List;

/** Persisted Productivity Score (ADR 0005), returned in the meeting detail. */
public record ProductivityAssessmentResponse(
        int score,
        String band,
        List<ProductivityCoverageResponse> coverage,
        Double offTopicRatio,
        Double decisionDensity,
        String rationale) {}

package br.com.nora.api.api.dto.meeting;

import java.util.List;

/** Productivity Score persistido (ADR 0005), retornado no detalhe da reuniao. */
public record ProductivityAssessmentResponse(
        int score,
        String band,
        List<ProductivityCoverageResponse> coverage,
        Double offTopicRatio,
        Double decisionDensity,
        String rationale) {}

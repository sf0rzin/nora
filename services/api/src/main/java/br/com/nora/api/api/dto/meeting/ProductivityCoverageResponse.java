package br.com.nora.api.api.dto.meeting;

/** Individual coverage of one expected outcome (ADR 0005). */
public record ProductivityCoverageResponse(
        String expectedOutcome, String status, String evidence) {}

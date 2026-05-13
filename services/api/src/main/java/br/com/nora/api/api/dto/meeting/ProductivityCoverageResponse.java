package br.com.nora.api.api.dto.meeting;

/** Cobertura individual de um outcome esperado (ADR 0005). */
public record ProductivityCoverageResponse(
        String expectedOutcome, String status, String evidence) {}

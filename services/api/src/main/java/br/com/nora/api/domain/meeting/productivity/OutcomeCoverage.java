package br.com.nora.api.domain.meeting.productivity;

/**
 * Cobertura de um outcome esperado conforme avaliacao do worker. Imutavel, value object filho de
 * {@link ProductivityAssessment}.
 */
public record OutcomeCoverage(
        String expectedOutcome, CoverageStatus status, String evidence, int position) {

    private static final int OUTCOME_MIN = 3;
    private static final int OUTCOME_MAX = 240;
    private static final int EVIDENCE_MAX = 500;

    public OutcomeCoverage {
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            throw new IllegalArgumentException("expectedOutcome is required");
        }
        String trimmedOutcome = expectedOutcome.trim();
        if (trimmedOutcome.length() < OUTCOME_MIN || trimmedOutcome.length() > OUTCOME_MAX) {
            throw new IllegalArgumentException(
                    "expectedOutcome length must be between "
                            + OUTCOME_MIN
                            + " and "
                            + OUTCOME_MAX);
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        String trimmedEvidence = evidence == null ? null : evidence.trim();
        if (trimmedEvidence != null && trimmedEvidence.isEmpty()) {
            trimmedEvidence = null;
        }
        if (trimmedEvidence != null && trimmedEvidence.length() > EVIDENCE_MAX) {
            throw new IllegalArgumentException(
                    "evidence must be at most " + EVIDENCE_MAX + " chars");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        expectedOutcome = trimmedOutcome;
        evidence = trimmedEvidence;
    }
}

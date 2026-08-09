package br.com.nora.api.domain.meeting.productivity;

/**
 * Expected outcome of a meeting (user input). Immutable, value object child of {@link MeetingGoal}.
 * Position preserved to keep display order.
 */
public record ExpectedOutcome(String text, int position) {

    private static final int TEXT_MIN = 3;
    private static final int TEXT_MAX = 240;

    public ExpectedOutcome {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("expected outcome text is required");
        }
        String trimmed = text.trim();
        if (trimmed.length() < TEXT_MIN || trimmed.length() > TEXT_MAX) {
            throw new IllegalArgumentException(
                    "expected outcome length must be between " + TEXT_MIN + " and " + TEXT_MAX);
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        text = trimmed;
    }
}

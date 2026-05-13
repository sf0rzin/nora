package br.com.nora.api.domain.meeting.productivity;

/**
 * Outcome esperado de uma reuniao (entrada do usuario). Imutavel, value object filho de {@link
 * MeetingGoal}. Posicao preservada para manter ordem de exibicao.
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

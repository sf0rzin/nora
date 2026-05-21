package br.com.nora.api.domain.customer;

/**
 * Objecao levantada pelo cliente conforme avaliacao do worker. Imutavel, value object filho de
 * {@link CustomerConfidenceAssessment} (ADR 0015).
 */
public record Objection(
        ObjectionType type,
        String quote,
        ObjectionSeverity severity,
        String competitor,
        int position) {

    private static final int QUOTE_MIN = 5;
    private static final int QUOTE_MAX = 500;
    private static final int COMPETITOR_MAX = 120;

    public Objection {
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        if (quote == null || quote.isBlank()) {
            throw new IllegalArgumentException("quote is required");
        }
        String trimmedQuote = quote.trim();
        if (trimmedQuote.length() < QUOTE_MIN || trimmedQuote.length() > QUOTE_MAX) {
            throw new IllegalArgumentException(
                    "quote length must be between " + QUOTE_MIN + " and " + QUOTE_MAX);
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        String trimmedCompetitor = competitor == null ? null : competitor.trim();
        if (trimmedCompetitor != null && trimmedCompetitor.isEmpty()) {
            trimmedCompetitor = null;
        }
        if (trimmedCompetitor != null && trimmedCompetitor.length() > COMPETITOR_MAX) {
            throw new IllegalArgumentException(
                    "competitor must be at most " + COMPETITOR_MAX + " chars");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        quote = trimmedQuote;
        competitor = trimmedCompetitor;
    }
}

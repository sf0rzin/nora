package br.com.nora.api.domain.customer;

/**
 * Sinal de compra detectado na reuniao conforme avaliacao do worker. Imutavel, value object filho
 * de {@link CustomerConfidenceAssessment} (ADR 0015).
 */
public record BuyingSignal(BuyingSignalType type, String quote, Double weight, int position) {

    private static final int QUOTE_MIN = 5;
    private static final int QUOTE_MAX = 500;

    public BuyingSignal {
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
        if (weight != null && (weight < 0.0 || weight > 1.0)) {
            throw new IllegalArgumentException("weight must be in [0,1]");
        }
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        quote = trimmedQuote;
    }
}

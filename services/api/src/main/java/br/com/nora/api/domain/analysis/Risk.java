package br.com.nora.api.domain.analysis;

/** Risk identified by the analysis. Severity and category are closed enums. */
public record Risk(String text, Severity severity, RiskCategory category, String sourceQuote) {

    public Risk {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("risk text is required");
        }
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (sourceQuote == null || sourceQuote.isBlank()) {
            throw new IllegalArgumentException("sourceQuote is required");
        }
    }
}

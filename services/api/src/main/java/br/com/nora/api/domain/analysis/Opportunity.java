package br.com.nora.api.domain.analysis;

/** Oportunidade comercial identificada pela analise. */
public record Opportunity(
        String text, Severity estimatedValue, OpportunityCategory category, String sourceQuote) {

    public Opportunity {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("opportunity text is required");
        }
        if (estimatedValue == null) {
            throw new IllegalArgumentException("estimatedValue is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (sourceQuote == null || sourceQuote.isBlank()) {
            throw new IllegalArgumentException("sourceQuote is required");
        }
    }
}

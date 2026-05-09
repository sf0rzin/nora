package br.com.nora.api.domain.analysis;

/** Decisao tomada na reuniao identificada pela analise. */
public record Decision(String text, double confidence) {

    public Decision {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("decision text is required");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0]");
        }
    }
}

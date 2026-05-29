package br.com.nora.api.application.platform;

/**
 * Erro de validação de plataforma. {@code unprocessable=true} → 422 (regra de domínio violada, ex.:
 * bindar analysis a modelo non-strict); {@code false} → 400 (input malformado, ex.: groupBy/serviço
 * inválido).
 */
public class PlatformValidationException extends RuntimeException {

    private final boolean unprocessable;

    public PlatformValidationException(String message, boolean unprocessable) {
        super(message);
        this.unprocessable = unprocessable;
    }

    public boolean isUnprocessable() {
        return unprocessable;
    }
}

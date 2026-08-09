package br.com.nora.api.application.platform;

/**
 * Platform validation error. {@code unprocessable=true} → 422 (domain rule violated, e.g. binding
 * analysis to a non-strict model); {@code false} → 400 (malformed input, e.g. invalid
 * groupBy/service).
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

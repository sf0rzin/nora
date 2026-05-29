package br.com.nora.api.application.platform;

/** Conflito (modelo duplicado, ou DELETE de modelo bindado) → 409. */
public class PlatformConflictException extends RuntimeException {
    public PlatformConflictException(String message) {
        super(message);
    }
}

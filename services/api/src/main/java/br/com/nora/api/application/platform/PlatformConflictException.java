package br.com.nora.api.application.platform;

/** Conflict (duplicate model, or DELETE of a bound model) → 409. */
public class PlatformConflictException extends RuntimeException {
    public PlatformConflictException(String message) {
        super(message);
    }
}

package br.com.nora.api.application.platform;

/** Platform resource not found → 404. */
public class PlatformNotFoundException extends RuntimeException {
    public PlatformNotFoundException(String message) {
        super(message);
    }
}

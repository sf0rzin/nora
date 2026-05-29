package br.com.nora.api.application.platform;

/** Recurso de plataforma não encontrado → 404. */
public class PlatformNotFoundException extends RuntimeException {
    public PlatformNotFoundException(String message) {
        super(message);
    }
}

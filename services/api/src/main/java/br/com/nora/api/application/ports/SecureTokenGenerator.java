package br.com.nora.api.application.ports;

/**
 * Gera tokens criptograficamente seguros (URL-safe) e seu hash determinístico para armazenamento.
 *
 * <p>O token cru e enviado ao usuario por e-mail; apenas o hash e persistido.
 */
public interface SecureTokenGenerator {

    record GeneratedToken(String rawToken, String hash) {}

    GeneratedToken generate();

    String hash(String rawToken);
}

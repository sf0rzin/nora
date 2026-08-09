package br.com.nora.api.application.ports;

/**
 * Generates cryptographically secure (URL-safe) tokens and their deterministic hash for storage.
 *
 * <p>The raw token is sent to the user by e-mail; only the hash is persisted.
 */
public interface SecureTokenGenerator {

    record GeneratedToken(String rawToken, String hash) {}

    GeneratedToken generate();

    String hash(String rawToken);
}

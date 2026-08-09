package br.com.nora.api.application.ports;

/** Port for password hashing. Implemented by BCrypt in production. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}

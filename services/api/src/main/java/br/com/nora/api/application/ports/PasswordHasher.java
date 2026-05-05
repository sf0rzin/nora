package br.com.nora.api.application.ports;

/** Porta para hashing de senha. Implementada por BCrypt em produçao. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}

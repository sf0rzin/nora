package br.com.nora.api.domain.identity;

/** User status in the tenant. Mirrors the CHECK constraint of the users table (V002). */
public enum UserStatus {
    ACTIVE,
    INVITED,
    DISABLED
}

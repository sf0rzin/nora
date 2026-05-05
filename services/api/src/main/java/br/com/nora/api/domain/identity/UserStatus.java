package br.com.nora.api.domain.identity;

/** Status do usuario no tenant. Espelha o CHECK constraint da tabela users (V002). */
public enum UserStatus {
    ACTIVE,
    INVITED,
    DISABLED
}

package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request. `password` tem cap @Size(max=128) para evitar DoS por hashing prolongado: BCrypt
 * trunca em 72 bytes mas string gigante ainda gasta CPU validando o input antes.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {}

package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login request. `password` has a @Size(max=128) cap to avoid DoS via prolonged hashing: BCrypt
 * truncates at 72 bytes but a huge string still burns CPU validating the input beforehand.
 */
public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {}

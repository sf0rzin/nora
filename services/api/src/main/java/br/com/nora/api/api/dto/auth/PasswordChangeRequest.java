package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Authenticated password change (Security tab). The new password policy is validated in the
 * application.
 */
public record PasswordChangeRequest(
        @NotBlank String currentPassword, @NotBlank String newPassword) {}

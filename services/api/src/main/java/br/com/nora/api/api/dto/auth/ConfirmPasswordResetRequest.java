package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmPasswordResetRequest(
        @NotBlank String token, @NotBlank @Size(min = 10, max = 128) String newPassword) {}

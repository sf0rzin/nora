package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 10, max = 128) String password,
        @Size(max = 120) String displayName,
        /** Workspace name (becomes the tenant name). Empty => personal tenant. */
        @Size(max = 120) String companyName,
        /** Usage intent collected during onboarding (individual/team/company) — telemetry. */
        @Size(max = 32) String role) {}

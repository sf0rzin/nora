package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
public record SignupResponse(
        UUID userId, UUID tenantId, String message, String emailVerificationDevToken) {}

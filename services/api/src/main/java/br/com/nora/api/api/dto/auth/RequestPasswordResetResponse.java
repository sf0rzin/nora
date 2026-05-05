package br.com.nora.api.api.dto.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonInclude(Include.NON_NULL)
public record RequestPasswordResetResponse(String message, String passwordResetDevToken) {}

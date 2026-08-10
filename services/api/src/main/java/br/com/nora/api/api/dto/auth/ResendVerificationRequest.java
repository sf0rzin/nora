package br.com.nora.api.api.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Verification e-mail resend (public; indistinguishable response, anti-enumeration).
 *
 * <p>Same constraints as the sibling DTOs of the public account endpoints ({@link SignupRequest},
 * {@link RequestPasswordResetRequest}): the value reaches the per-address rate limiter, which
 * retains it as a bucket key, so it is bounded to the longest address RFC 5321 allows before it
 * gets there.
 */
public record ResendVerificationRequest(@NotBlank @Email @Size(max = 254) String email) {}

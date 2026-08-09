package br.com.nora.api.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Update of the user's own profile (PATCH /users/me). Today just the display name. */
public record UpdateMeRequest(@NotBlank @Size(max = 120) String displayName) {}

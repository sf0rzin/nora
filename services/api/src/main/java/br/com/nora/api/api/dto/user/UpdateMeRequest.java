package br.com.nora.api.api.dto.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Atualizacao do proprio perfil (PATCH /users/me). Hoje so o nome de exibicao. */
public record UpdateMeRequest(@NotBlank @Size(max = 120) String displayName) {}

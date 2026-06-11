package br.com.nora.api.api.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Renomear o workspace (PUT /tenant/name). O slug e imutavel. */
public record TenantNameUpdateRequest(@NotBlank @Size(max = 120) String name) {}

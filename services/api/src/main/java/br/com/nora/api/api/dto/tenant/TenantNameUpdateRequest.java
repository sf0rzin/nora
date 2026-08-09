package br.com.nora.api.api.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Rename the workspace (PUT /tenant/name). The slug is immutable. */
public record TenantNameUpdateRequest(@NotBlank @Size(max = 120) String name) {}

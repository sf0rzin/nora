package br.com.nora.api.api.dto.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /iam/groups}. Same bounds, and same reason, as {@link CreatePolicyRequest}.
 */
public record CreateGroupRequest(
        @NotBlank(message = "name is required") @Size(max = 120) String name,
        @Size(max = 500) String description) {}

package br.com.nora.api.api.dto.iam;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Payload {@code POST /iam/users/invite}. Schema em {@code
 * docs/api/examples/iam-invite-request.json}.
 */
public record InviteUserRequest(
        @NotBlank @Email @Size(max = 254) String email,
        List<UUID> groupIds,
        Integer expiresInDays) {}

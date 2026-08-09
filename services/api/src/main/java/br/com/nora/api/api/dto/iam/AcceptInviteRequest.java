package br.com.nora.api.api.dto.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload {@code POST /iam/invites/{token}/accept}. Schema in {@code
 * docs/api/examples/iam-invite-accept-request.json}. {@code displayName} is optional — if omitted,
 * uses the local-part of the e-mail.
 */
public record AcceptInviteRequest(
        @Size(max = 120) String displayName,
        @NotBlank @Size(min = 10, max = 128) String password) {}

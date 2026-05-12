package br.com.nora.api.api.dto.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload {@code POST /iam/invites/{token}/accept}. Schema em {@code
 * docs/api/examples/iam-invite-accept-request.json}. {@code displayName} e opcional — se omitido,
 * usa o local-part do e-mail.
 */
public record AcceptInviteRequest(
        @Size(max = 120) String displayName,
        @NotBlank @Size(min = 10, max = 128) String password) {}

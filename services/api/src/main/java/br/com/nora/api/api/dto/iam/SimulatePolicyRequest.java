package br.com.nora.api.api.dto.iam;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code POST /iam/simulate} (US43).
 *
 * <p>The bounds matter more here than they look: the three text fields go straight into the policy
 * evaluator, where the resource is matched by a regex built from the pattern, and the context map
 * is crossed against every condition of every attached statement. They are bounded at the edge so
 * the evaluator is never asked to work over an input no real policy could describe.
 *
 * @param userId subject of the simulation — has to be a user of the caller's own tenant
 * @param action IAM action to test, e.g. {@code meeting:read}
 * @param resource resource ARN to test, e.g. {@code nora:tenant/TENANT:meeting/MEETING}
 * @param context attributes the statement conditions read; may be null or empty
 */
public record SimulatePolicyRequest(
        @NotNull(message = "userId is required") UUID userId,
        @NotBlank(message = "action is required") @Size(max = 200) String action,
        @NotBlank(message = "resource is required") @Size(max = 400) String resource,
        @Size(max = 50, message = "context must have at most 50 entries")
                Map<@Size(max = 64) String, @Size(max = 256) String> context) {}

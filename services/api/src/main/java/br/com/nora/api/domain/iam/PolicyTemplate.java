package br.com.nora.api.domain.iam;

import java.util.Objects;

/**
 * A built-in starting point for a policy document (US41).
 *
 * <p>A template is not a policy and never becomes one on its own. It carries a {@link
 * PolicyDocument} already bound to one tenant, which the caller submits to {@code POST
 * /iam/policies} exactly as if it had been typed by hand. The policy that results is created,
 * versioned, attached and evaluated by the same code as every other policy — there is no second
 * evaluation path, because there is no second kind of policy.
 *
 * <p>The {@code id} doubles as the suggested policy name: it is kebab-case, unique in the
 * catalogue, and the tenant is free to rename it before creating the policy.
 */
public record PolicyTemplate(String id, String description, PolicyDocument document) {

    public PolicyTemplate {
        Objects.requireNonNull(id, "id required");
        Objects.requireNonNull(description, "description required");
        Objects.requireNonNull(document, "document required");
    }
}

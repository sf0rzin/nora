package br.com.nora.api.api.dto.tenant;

import jakarta.validation.constraints.Size;

/**
 * Update payload for the tenant's corporate domain (US32). {@code allowedEmailDomain} may be {@code
 * null} to remove the restriction.
 */
public record TenantDomainUpdateRequest(@Size(max = 255) String allowedEmailDomain) {}

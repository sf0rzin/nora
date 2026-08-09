package br.com.nora.api.api.dto.tenant;

import java.time.Instant;
import java.util.UUID;

/** Response of PUT /tenant/domain (US32). */
public record TenantDomainUpdateResponse(
        UUID tenantId, String allowedEmailDomain, Instant updatedAt, UUID updatedBy) {}

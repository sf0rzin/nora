package br.com.nora.api.api.dto.tenant;

import java.util.UUID;

/** Resposta de GET /tenant/domain (US32). */
public record TenantDomainResponse(UUID tenantId, String allowedEmailDomain) {}

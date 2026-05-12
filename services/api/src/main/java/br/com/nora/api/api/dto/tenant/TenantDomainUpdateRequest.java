package br.com.nora.api.api.dto.tenant;

import jakarta.validation.constraints.Size;

/**
 * Payload de atualizacao do dominio corporativo do tenant (US32). {@code allowedEmailDomain} pode
 * ser {@code null} para remover a restricao.
 */
public record TenantDomainUpdateRequest(@Size(max = 255) String allowedEmailDomain) {}

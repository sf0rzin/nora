package br.com.nora.api.application.tenant;

import java.util.UUID;

/**
 * Falhas de configuracao geral do tenant (US32 e diante). Distinto de {@link
 * TenantContextException} que cobre o subdominio "contexto comercial".
 */
public sealed class TenantException extends RuntimeException {

    private final String code;

    protected TenantException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Tenant inexistente. */
    public static final class NotFound extends TenantException {
        public NotFound(UUID tenantId) {
            super("TENANT_NOT_FOUND", "tenant not found: " + tenantId);
        }
    }

    /**
     * Formato de dominio invalido (US32). Mapeado para HTTP 422 para sinalizar que o payload e
     * sintaticamente correto mas semanticamente nao aceitavel.
     */
    public static final class InvalidDomain extends TenantException {
        public InvalidDomain(String detail) {
            super("TENANT_DOMAIN_INVALID", detail);
        }
    }
}

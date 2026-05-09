package br.com.nora.api.application.tenant;

import java.util.UUID;

/** Falhas relacionadas ao contexto do tenant. */
public sealed class TenantContextException extends RuntimeException {

    private final String code;

    protected TenantContextException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends TenantContextException {
        public NotFound(UUID tenantId) {
            super("TENANT_CONTEXT_NOT_FOUND", "tenant context not found for tenant " + tenantId);
        }
    }

    public static final class Invalid extends TenantContextException {
        public Invalid(String detail) {
            super("TENANT_CONTEXT_INVALID", detail);
        }
    }
}

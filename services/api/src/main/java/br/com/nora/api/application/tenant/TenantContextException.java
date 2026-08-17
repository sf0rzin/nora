package br.com.nora.api.application.tenant;

import java.util.UUID;

/** Failures related to the tenant context. */
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

    /**
     * No such version in the caller tenant's context history (US31).
     *
     * <p>The message carries the number and never the tenant: a version number that belongs to
     * another tenant has to be indistinguishable from one that does not exist at all, or the
     * response confirms the existence of the other tenant's history.
     */
    public static final class VersionNotFound extends TenantContextException {
        public VersionNotFound(int version) {
            super(
                    "TENANT_CONTEXT_VERSION_NOT_FOUND",
                    "tenant context version " + version + " not found");
        }
    }
}

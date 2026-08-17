package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.domain.tenant.TenantContextVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence of the tenant's commercial context (US14/US30). 1-1 with tenants. */
public interface TenantContextRepository {

    /**
     * Upserts the context AND appends its history entry in the same transaction (US31).
     *
     * <p>The two writes are one operation on purpose: a document saved without its version, or a
     * version without its document, is a trail that disagrees with the thing it is supposed to
     * describe. Callers cannot get this wrong by forgetting the second call.
     */
    TenantContext save(TenantContext context);

    Optional<TenantContext> findByTenantId(UUID tenantId);

    /**
     * History of the tenant's live context, newest version first (US31).
     *
     * <p>Empty for a tenant that has never saved a context. Metadata only — see {@link
     * TenantContextVersion}.
     */
    List<TenantContextVersion> listVersions(UUID tenantId);

    /** One version of the tenant's live context, with the document it froze (US31). */
    Optional<TenantContextVersion.Detail> findVersion(UUID tenantId, int version);
}

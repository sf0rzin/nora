package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    boolean existsBySlug(String slug);

    Tenant save(Tenant tenant);

    /** IDs of all ACTIVE tenants (soft-delete filtered out). Used by the retention sweeper. */
    List<UUID> allActiveTenantIds();

    /**
     * PHYSICAL hard-delete of the tenant (LGPD: account deletion, GOAL Phase 3). The FK CASCADE
     * purges EVERYTHING that references tenants(id): users, meetings/transcripts (PII), analyses,
     * chat, workflows, OAuth connections, tokens. Irreversible — the caller validates password +
     * personal tenant beforehand.
     */
    void hardDelete(UUID tenantId);
}

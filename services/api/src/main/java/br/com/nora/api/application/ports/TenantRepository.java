package br.com.nora.api.application.ports;

import br.com.nora.api.domain.tenant.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    Optional<Tenant> findById(UUID id);

    boolean existsBySlug(String slug);

    Tenant save(Tenant tenant);

    /** IDs de todos os tenants ATIVOS (soft-delete filtrado). Usado pelo sweeper de retenção. */
    List<UUID> allActiveTenantIds();

    /**
     * Hard-delete FÍSICO do tenant (LGPD: exclusão de conta, GOAL Fase 3). O FK CASCADE purga TUDO
     * que referencia tenants(id): users, meetings/transcripts (PII), análises, chat, workflows,
     * conexões OAuth, tokens. Irreversível — o caller valida senha + tenant pessoal antes.
     */
    void hardDelete(UUID tenantId);
}

package br.com.nora.api.infrastructure.persistence.tenant;

import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.domain.tenant.Tenant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TenantRepositoryAdapter implements TenantRepository {

    private final TenantJpaRepository jpa;

    @PersistenceContext private EntityManager em;

    public TenantRepositoryAdapter(TenantJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public boolean existsBySlug(String slug) {
        return jpa.existsBySlug(slug);
    }

    @Override
    public List<UUID> allActiveTenantIds() {
        return jpa.findAllActiveIds();
    }

    @Override
    public Tenant save(Tenant tenant) {
        TenantJpaEntity entity =
                new TenantJpaEntity(
                        tenant.id(),
                        tenant.name(),
                        tenant.slug(),
                        tenant.status(),
                        tenant.plan(),
                        tenant.allowedEmailDomain(),
                        tenant.createdAt(),
                        tenant.updatedAt());
        TenantJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional
    public void hardDelete(UUID tenantId) {
        // Native queries on purpose (bypasses @SQLDelete/soft-delete) in a deterministic order:
        // legacy FKs without CASCADE block the direct path — users.tenant_id is ON DELETE
        // RESTRICT (V002), meetings have a composite FK to users (V015) and iam_user_invitations
        // references users with no action (V010). We delete first what references users, then
        // users, and finally the tenant — the rest (transcripts/analyses/chat/workflows/tokens)
        // cascades from their own parent tables. All in the SAME transaction: either everything
        // goes, or nothing does.
        em.createNativeQuery("DELETE FROM meetings WHERE tenant_id = :id")
                .setParameter("id", tenantId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM iam_user_invitations WHERE tenant_id = :id")
                .setParameter("id", tenantId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM users WHERE tenant_id = :id")
                .setParameter("id", tenantId)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM tenants WHERE id = :id")
                .setParameter("id", tenantId)
                .executeUpdate();
    }

    private Tenant toDomain(TenantJpaEntity e) {
        return new Tenant(
                e.getId(),
                e.getName(),
                e.getSlug(),
                e.getStatus(),
                e.getPlan(),
                e.getAllowedEmailDomain(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}

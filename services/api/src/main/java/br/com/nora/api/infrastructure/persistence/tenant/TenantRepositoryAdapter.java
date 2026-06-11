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
        // Native queries de propósito (bypassa @SQLDelete/soft-delete) em ordem determinística:
        // FKs legadas sem CASCADE bloqueiam o caminho direto — users.tenant_id é ON DELETE
        // RESTRICT (V002), meetings têm FK composta para users (V015) e iam_user_invitations
        // referencia users sem action (V010). Apagamos primeiro o que referencia users, depois
        // users, e por fim o tenant — o resto (transcripts/análises/chat/workflows/tokens)
        // cascateia das próprias tabelas-mãe. Tudo na MESMA transação: ou some tudo, ou nada.
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

package br.com.nora.api.infrastructure.persistence.tenant;

import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.domain.tenant.Tenant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class TenantRepositoryAdapter implements TenantRepository {

    private final TenantJpaRepository jpa;

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

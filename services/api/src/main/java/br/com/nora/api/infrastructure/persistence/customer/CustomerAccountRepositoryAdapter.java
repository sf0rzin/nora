package br.com.nora.api.infrastructure.persistence.customer;

import br.com.nora.api.application.ports.CustomerAccountRepository;
import br.com.nora.api.domain.customer.CustomerAccount;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CustomerAccountRepositoryAdapter implements CustomerAccountRepository {

    private final CustomerAccountJpaRepository jpa;

    @PersistenceContext private EntityManager em;

    public CustomerAccountRepositoryAdapter(CustomerAccountJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public CustomerAccount save(CustomerAccount account) {
        CustomerAccountJpaEntity saved = jpa.save(toEntity(account));
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerAccount> findByTenantAndLowerName(UUID tenantId, String name) {
        return jpa.findByTenantIdAndLowerName(tenantId, name).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerAccount> findById(UUID id, UUID tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void linkMeeting(UUID meetingId, UUID accountId, UUID tenantId) {
        // Idempotent: composite PK (meeting_id, customer_account_id). ON CONFLICT DO NOTHING
        // avoids an error on re-link (e.g. reprocessing the same meeting).
        em.createNativeQuery(
                        "INSERT INTO meeting_account_links (meeting_id, customer_account_id, "
                                + "tenant_id) VALUES (:m, :a, :t) ON CONFLICT DO NOTHING")
                .setParameter("m", meetingId)
                .setParameter("a", accountId)
                .setParameter("t", tenantId)
                .executeUpdate();
    }

    private CustomerAccountJpaEntity toEntity(CustomerAccount a) {
        CustomerAccountJpaEntity e = new CustomerAccountJpaEntity();
        e.setId(a.id());
        e.setTenantId(a.tenantId());
        e.setName(a.name());
        e.setOwnerUserId(a.ownerUserId());
        e.setStage(a.stage());
        e.setCreatedAt(a.createdAt());
        e.setUpdatedAt(a.updatedAt());
        return e;
    }

    private CustomerAccount toDomain(CustomerAccountJpaEntity e) {
        return new CustomerAccount(
                e.getId(),
                e.getTenantId(),
                e.getName(),
                e.getOwnerUserId(),
                e.getStage(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}

package br.com.nora.api.infrastructure.persistence.identity;

import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

    @PersistenceContext private EntityManager em;

    public UserRepositoryAdapter(UserJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jpa.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<User> findByEmail(Email email) {
        return jpa.findByEmail(email.value()).map(this::toDomain);
    }

    @Override
    public User save(User user) {
        UserJpaEntity entity =
                jpa.findById(user.id())
                        .map(
                                e -> {
                                    e.setPasswordHash(user.passwordHash());
                                    e.setDisplayName(user.displayName());
                                    e.setStatus(user.status());
                                    e.setEmailVerifiedAt(user.emailVerifiedAt());
                                    e.setUpdatedAt(user.updatedAt());
                                    return e;
                                })
                        .orElseGet(
                                () ->
                                        new UserJpaEntity(
                                                user.id(),
                                                user.tenantId(),
                                                user.email().value(),
                                                user.passwordHash(),
                                                user.displayName(),
                                                user.status(),
                                                user.emailVerifiedAt(),
                                                user.createdAt(),
                                                user.updatedAt()));
        UserJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    private User toDomain(UserJpaEntity e) {
        return new User(
                e.getId(),
                e.getTenantId(),
                Email.of(e.getEmail()),
                e.getPasswordHash(),
                e.getDisplayName(),
                e.getStatus(),
                e.getEmailVerifiedAt(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    @Override
    @Transactional
    public void markAsRoot(UUID userId, UUID tenantId) {
        em.createNativeQuery(
                        "UPDATE users SET is_root = TRUE, updated_at = NOW() "
                                + "WHERE id = :id AND tenant_id = :tenantId")
                .setParameter("id", userId)
                .setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    public int countByTenant(UUID tenantId) {
        Object result =
                em.createNativeQuery("SELECT COUNT(*) FROM users WHERE tenant_id = :tenantId")
                        .setParameter("tenantId", tenantId)
                        .getSingleResult();
        return ((Number) result).intValue();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isRoot(UUID userId, UUID tenantId) {
        Object result =
                em.createNativeQuery(
                                "SELECT is_root FROM users WHERE id = :id AND tenant_id = :tenantId")
                        .setParameter("id", userId)
                        .setParameter("tenantId", tenantId)
                        .getResultStream()
                        .findFirst()
                        .orElse(Boolean.FALSE);
        return Boolean.TRUE.equals(result);
    }
}

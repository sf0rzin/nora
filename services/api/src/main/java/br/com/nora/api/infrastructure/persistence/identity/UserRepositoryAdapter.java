package br.com.nora.api.infrastructure.persistence.identity;

import br.com.nora.api.application.ports.UserRepository;
import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

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
}

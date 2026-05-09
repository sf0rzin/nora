package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia para o agregado User. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    User save(User user);

    /** Marca o usuario como Root do tenant. So pode existir um Root ativo por tenant. */
    void markAsRoot(UUID userId, UUID tenantId);

    /** Indica se o usuario e o Root do tenant. */
    boolean isRoot(UUID userId, UUID tenantId);
}

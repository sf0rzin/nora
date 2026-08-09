package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the User aggregate. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    User save(User user);

    /** Marks the user as the tenant's Root. Only one active Root can exist per tenant. */
    void markAsRoot(UUID userId, UUID tenantId);

    /** Whether the user is the tenant's Root. */
    boolean isRoot(UUID userId, UUID tenantId);

    /** How many users the tenant has. Guard for account deletion (personal tenant only, 1 user). */
    int countByTenant(UUID tenantId);
}

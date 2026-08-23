package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for the User aggregate. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    /**
     * The tenant's users, by display name, capped at {@code limit} rows.
     *
     * <p>This exists so IAM has a directory. Half the IAM surface — adding a member to a group,
     * attaching a policy to a user, setting a permission boundary, running the simulator — takes a
     * user id, and no endpoint returned one: {@code /users/me} is the caller's own row and the only
     * other place a user id appeared anywhere in the product was the {@code actorUserId} column of
     * the audit log. In practice an operator needed database access to use features shipped as
     * done.
     */
    List<User> listByTenant(UUID tenantId, int limit);

    Optional<User> findByEmail(Email email);

    User save(User user);

    /** Marks the user as the tenant's Root. Only one active Root can exist per tenant. */
    void markAsRoot(UUID userId, UUID tenantId);

    /** Whether the user is the tenant's Root. */
    boolean isRoot(UUID userId, UUID tenantId);

    /** How many users the tenant has. Guard for account deletion (personal tenant only, 1 user). */
    int countByTenant(UUID tenantId);
}

package br.com.nora.api.application.ports;

import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for user invitations (US06). All operations are scoped by tenant. We persist
 * only the SHA-256 of the token (column {@code token_hash}, indexed); the raw token never gets
 * here.
 */
public interface InvitationRepository {

    /** Finds an invitation by the SHA-256 of the token (accept flow). O(1) lookup by index. */
    Optional<IamInvitation> findByTokenHash(String tokenHash);

    /** Finds an invitation by id within the tenant (revocation, administrative lookup). */
    Optional<IamInvitation> findById(UUID invitationId, UUID tenantId);

    /**
     * Returns the active PENDING invitation for the e-mail in the tenant, if any. Used for
     * idempotency: avoid creating two simultaneous pending invitations for the same recipient.
     */
    Optional<IamInvitation> findPendingByEmail(UUID tenantId, String email);

    /** Persists the aggregate (insert or update). Returns the saved instance. */
    IamInvitation save(IamInvitation invitation);

    /**
     * Lists the tenant's invitations. When {@code status == null} returns all of them. Results are
     * ordered by {@code invited_at} desc.
     */
    List<IamInvitation> listByTenant(UUID tenantId, InvitationStatus status);
}

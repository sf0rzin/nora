package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.RefreshToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for stateful refresh tokens (Round 2 / Subphase 1.3 A).
 *
 * <p>The raw token never passes through here: only the SHA-256 hash.
 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Lookup by id. Used to inspect the child pointed to by {@code replacedById} and decide whether
     * the rotation chain is still alive.
     */
    Optional<RefreshToken> findById(UUID id);

    /**
     * Lists only non-revoked tokens; expired ones may show up and must be filtered by the caller.
     */
    List<RefreshToken> findActiveByUserId(UUID userId);

    /**
     * Bulk-revokes all of the user's non-revoked tokens (e.g. logout-all-sessions, password
     * change). Returns the number of tokens actually revoked.
     */
    int revokeAllByUserId(UUID userId, java.time.Instant now);

    /**
     * Bulk-revokes all tokens of the family (rotation chain). Used when reuse is detected: a
     * revoked token from the family was presented, indicating probable compromise of the chain.
     * Returns the number of tokens actually revoked.
     */
    int revokeAllByFamilyId(UUID familyId, java.time.Instant now);
}

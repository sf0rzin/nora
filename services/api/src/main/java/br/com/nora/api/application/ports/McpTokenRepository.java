package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.McpToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the MCP bearer credentials of ADR 0041 §3 (migration V029).
 *
 * <p>The raw token never passes through here: only its SHA-256 hash.
 *
 * <p>{@link #findByTokenHash} is deliberately NOT tenant-scoped, and it is the only method here
 * that is not. Resolving the credential is how a request learns which tenant it belongs to, so
 * there is no tenant to scope by yet — the same position {@code refresh_tokens} and the invitation
 * lookup are in. Every other method takes the tenant explicitly.
 */
public interface McpTokenRepository {

    McpToken save(McpToken token);

    /** Lookup by hash for the edge exchange. Returns revoked and expired tokens too. */
    Optional<McpToken> findByTokenHash(String tokenHash);

    /** The caller's own tokens, newest first, revoked ones included so the list is honest. */
    List<McpToken> findByOwner(UUID tenantId, UUID userId);

    /** Scoped by owner on purpose: a token id alone must not be enough to revoke it. */
    Optional<McpToken> findByIdAndOwner(UUID id, UUID tenantId, UUID userId);
}

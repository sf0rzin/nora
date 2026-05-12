package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.RefreshToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistencia para refresh tokens stateful (Round 2 / Subfase 1.3 A).
 *
 * <p>O token cru nunca passa por aqui: apenas o hash SHA-256.
 */
public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Lista somente tokens nao revogados; expirados podem aparecer e devem ser filtrados pelo
     * caller.
     */
    List<RefreshToken> findActiveByUserId(UUID userId);

    /**
     * Revoga em lote todos os tokens nao revogados do usuario (ex.: logout-all-sessions, troca de
     * senha). Retorna o numero de tokens efetivamente revogados.
     */
    int revokeAllByUserId(UUID userId, java.time.Instant now);
}

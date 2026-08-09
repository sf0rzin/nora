package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import java.util.Optional;

/** Persistence port for one-time tokens (e-mail verification and password reset). */
public interface OneTimeTokenRepository {

    OneTimeToken save(OneTimeToken token);

    Optional<OneTimeToken> findByTokenHashAndPurpose(String tokenHash, Purpose purpose);

    /** Invalidates (consumes) all of a user's unused tokens for a purpose. */
    int invalidateActiveForUser(java.util.UUID userId, Purpose purpose, java.time.Instant now);
}

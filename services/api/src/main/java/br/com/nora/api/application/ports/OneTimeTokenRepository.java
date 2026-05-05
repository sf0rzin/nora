package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.OneTimeToken;
import br.com.nora.api.domain.identity.OneTimeToken.Purpose;
import java.util.Optional;

/** Porta de persistencia para tokens de uso unico (verificacao de e-mail e reset de senha). */
public interface OneTimeTokenRepository {

    OneTimeToken save(OneTimeToken token);

    Optional<OneTimeToken> findByTokenHashAndPurpose(String tokenHash, Purpose purpose);

    /** Invalida (consome) todos os tokens nao usados de um usuario para um proposito. */
    int invalidateActiveForUser(java.util.UUID userId, Purpose purpose, java.time.Instant now);
}

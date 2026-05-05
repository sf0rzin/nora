package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.User;
import java.time.Duration;
import java.util.List;

/** Porta para emissao de access tokens (JWT). */
public interface JwtIssuer {

    /**
     * Emite um token JWT contendo as claims minimas (sub=userId, tenantId, email, roles).
     *
     * @param user usuario autenticado
     * @param roles codigos de papel (ROOT, ADMIN, MANAGER, ANALYST, VIEWER)
     * @param ttl duracao do token
     * @return token JWT codificado
     */
    String issue(User user, List<String> roles, Duration ttl);
}

package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.User;
import java.time.Duration;
import java.util.List;

/** Port for issuing access tokens (JWT). */
public interface JwtIssuer {

    /**
     * Issues a JWT token containing the minimum claims (sub=userId, tenantId, email, roles).
     *
     * @param user authenticated user
     * @param roles role codes (ROOT, ADMIN, MANAGER, ANALYST, VIEWER)
     * @param ttl token duration
     * @return encoded JWT token
     */
    String issue(User user, List<String> roles, Duration ttl);
}

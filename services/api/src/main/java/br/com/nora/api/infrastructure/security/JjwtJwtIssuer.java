package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.domain.identity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Emite e valida JWTs HS256 com chave compartilhada (nora.security.jwt.secret). */
@Component
public class JjwtJwtIssuer implements JwtIssuer {

    private static final String CLAIM_TENANT = "tenantId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";

    private final SecretKey key;
    private final String issuer;

    /**
     * Placeholder publico que NUNCA deve aparecer em runtime fora de teste/local. Mantido em
     * sincronia com o default declarado em application.yml e .env.example apenas para que a
     * validacao a seguir consiga rejeita-lo explicitamente.
     */
    private static final String INSECURE_DEFAULT_SECRET = "change-me-please-min-32-chars-long-aaaa";

    public JjwtJwtIssuer(
            @Value("${nora.security.jwt.secret}") String secret,
            @Value("${spring.profiles.active:default}") String activeProfile) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "nora.security.jwt.secret must be at least 32 bytes (256 bits)");
        }
        boolean isLocalOrTest =
                activeProfile != null
                        && (activeProfile.contains("local") || activeProfile.contains("test"));
        if (INSECURE_DEFAULT_SECRET.equals(secret) && !isLocalOrTest) {
            throw new IllegalStateException(
                    "nora.security.jwt.secret esta com o valor placeholder padrao; defina"
                            + " JWT_SECRET com um segredo gerado por SecureRandom (>=32 bytes) antes de"
                            + " subir fora do profile local/test.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = "nora-api";
    }

    @Override
    public String issue(User user, List<String> roles, Duration ttl) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttl.toMillis());
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.id().toString())
                .claim(CLAIM_TENANT, user.tenantId().toString())
                .claim(CLAIM_EMAIL, user.email().value())
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /** Valida assinatura/expiracao e retorna as claims principais para o filtro. */
    public AuthenticatedPrincipal parse(String token) throws JwtException {
        Jws<Claims> jws =
                Jwts.parser()
                        .verifyWith(key)
                        .requireIssuer(issuer)
                        .build()
                        .parseSignedClaims(token);
        Claims c = jws.getPayload();
        UUID userId = UUID.fromString(c.getSubject());
        UUID tenantId = UUID.fromString(c.get(CLAIM_TENANT, String.class));
        String email = c.get(CLAIM_EMAIL, String.class);
        @SuppressWarnings("unchecked")
        List<String> roles =
                (List<String>) c.getOrDefault(CLAIM_ROLES, java.util.Collections.emptyList());
        return new AuthenticatedPrincipal(userId, tenantId, email, roles);
    }

    public record AuthenticatedPrincipal(
            UUID userId, UUID tenantId, String email, List<String> roles) {}
}

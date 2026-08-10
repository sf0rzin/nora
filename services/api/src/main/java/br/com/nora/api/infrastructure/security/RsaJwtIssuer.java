package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.JwtIssuer;
import br.com.nora.api.domain.identity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Issues and validates RS256 (asymmetric) JWTs.
 *
 * <p>In prod with external validators (e.g. other services that need to validate NORA tokens),
 * RS256 + JWKS endpoint lets each validator fetch the public key via {@code /.well-known/jwks.json}
 * without needing the shared secret.
 *
 * <p>Bean active when {@code nora.security.jwt.algorithm} = RS256. Private key loaded from the env
 * {@code JWT_RS256_PRIVATE_KEY_PEM} (PKCS#8 base64 format). In Azure prod, the secret is rendered
 * via Bicep from a Key Vault secret (future integration with KV native keys).
 */
@Component
@ConditionalOnProperty(name = "nora.security.jwt.algorithm", havingValue = "RS256")
public class RsaJwtIssuer implements JwtIssuer {

    private static final String CLAIM_TENANT = "tenantId";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_ROLES = "roles";

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final String kid;
    private final String issuer;

    public RsaJwtIssuer(
            @Value("${nora.security.jwt.rsa.private-key-pem}") String privateKeyPem,
            @Value("${nora.security.jwt.rsa.kid:nora-key-1}") String kid) {
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new IllegalStateException(
                    "nora.security.jwt.rsa.private-key-pem is required when algorithm=RS256."
                            + " Provide an RSA PKCS#8 key in PEM format (plain base64 or"
                            + " -----BEGIN PRIVATE KEY----- envelope).");
        }
        this.privateKey = parsePrivateKey(privateKeyPem);
        this.publicKey = derivePublicKey(privateKey);
        this.kid = kid;
        this.issuer = "nora-api";
    }

    @Override
    public String issue(User user, List<String> roles, Duration ttl) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + ttl.toMillis());
        return Jwts.builder()
                .header()
                .keyId(kid)
                .and()
                .issuer(issuer)
                .subject(user.id().toString())
                .claim(CLAIM_TENANT, user.tenantId().toString())
                .claim(CLAIM_EMAIL, user.email().value())
                .claim(CLAIM_ROLES, roles)
                .issuedAt(now)
                .expiration(exp)
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public JjwtJwtIssuer.AuthenticatedPrincipal parse(String token) throws JwtException {
        Jws<Claims> jws =
                Jwts.parser()
                        .verifyWith(publicKey)
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
        return new JjwtJwtIssuer.AuthenticatedPrincipal(userId, tenantId, email, roles);
    }

    /** Exposes the public key + kid for building the JWKS. */
    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String kid() {
        return kid;
    }

    private static RSAPrivateKey parsePrivateKey(String pem) {
        String normalized =
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s+", "");
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "nora.security.jwt.rsa.private-key-pem is not valid base64.", ex);
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to parse RSA private key PKCS#8 from"
                            + " nora.security.jwt.rsa.private-key-pem.",
                    ex);
        }
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        try {
            // Supports PKCS#8 with CRT key: extracts modulus and public exponent.
            if (privateKey instanceof java.security.interfaces.RSAPrivateCrtKey crt) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return (RSAPublicKey)
                        kf.generatePublic(
                                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            }
            throw new IllegalStateException(
                    "RSA private key must be CRT (standard PKCS#8 with embedded publicExponent)."
                            + " Generate one with `openssl genpkey -algorithm RSA -out key.pem` or"
                            + " similar.");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to derive RSA public key.", ex);
        }
    }
}

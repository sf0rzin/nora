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
 * Emite e valida JWTs RS256 (assimetricos).
 *
 * <p>Em prod com validators externos (ex.: outros servicos que precisam validar tokens da NORA),
 * RS256 + JWKS endpoint permite que cada validator busque a chave publica via {@code
 * /.well-known/jwks.json} sem precisar do segredo compartilhado.
 *
 * <p>Bean ativo quando {@code nora.security.jwt.algorithm} = RS256. Chave privada carregada do env
 * {@code JWT_RS256_PRIVATE_KEY_PEM} (formato PKCS#8 base64). Em Azure prod, o secret e renderizado
 * via Bicep a partir de um Key Vault secret (futura integracao com keys nativas do KV).
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
                    "nora.security.jwt.rsa.private-key-pem e obrigatorio quando algorithm=RS256."
                            + " Forneca uma chave RSA PKCS#8 em formato PEM (base64 puro ou"
                            + " envelope -----BEGIN PRIVATE KEY-----).");
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

    /** Expoe a chave publica + kid pra construcao do JWKS. */
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
                    "nora.security.jwt.rsa.private-key-pem nao e base64 valido.", ex);
        }
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Falha ao parsear chave RSA privada PKCS#8 de nora.security.jwt.rsa.private-key-pem.",
                    ex);
        }
    }

    private static RSAPublicKey derivePublicKey(RSAPrivateKey privateKey) {
        try {
            // Suporta PKCS#8 com CRT key: extrai modulus e expoente publico.
            if (privateKey instanceof java.security.interfaces.RSAPrivateCrtKey crt) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                return (RSAPublicKey)
                        kf.generatePublic(
                                new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            }
            throw new IllegalStateException(
                    "Chave RSA privada deve ser CRT (PKCS#8 padrao com publicExponent embutido)."
                            + " Gere com `openssl genpkey -algorithm RSA -out key.pem` ou similar.");
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao derivar chave RSA publica.", ex);
        }
    }
}

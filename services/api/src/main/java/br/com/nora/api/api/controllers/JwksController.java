package br.com.nora.api.api.controllers;

import br.com.nora.api.api.security.AuthorizationNotRequired;
import br.com.nora.api.infrastructure.security.RsaJwtIssuer;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public endpoint that exposes the RSA public key via JWKS (RFC 7517).
 *
 * <p>External validators (other services that validate tokens issued by NORA) fetch the public key
 * here instead of needing a shared secret. In HS256 mode the controller is not registered
 * (conditional bean).
 *
 * <p>Default path from the spec: {@code /.well-known/jwks.json}.
 */
@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(RsaJwtIssuer.class)
public class JwksController {

    private final RsaJwtIssuer issuer;

    public JwksController(RsaJwtIssuer issuer) {
        this.issuer = issuer;
    }

    @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthorizationNotRequired(reason = "Public: publishes the RSA PUBLIC key (RFC 7517).")
    public Map<String, Object> jwks() {
        RSAPublicKey pub = issuer.publicKey();
        Map<String, Object> key =
                Map.of(
                        "kty",
                        "RSA",
                        "use",
                        "sig",
                        "alg",
                        "RS256",
                        "kid",
                        issuer.kid(),
                        "n",
                        base64Url(pub.getModulus().toByteArray()),
                        "e",
                        base64Url(pub.getPublicExponent().toByteArray()));
        return Map.of("keys", List.of(key));
    }

    /**
     * JWKS requires base64url *without* padding. {@code BigInteger.toByteArray()} may carry a sign
     * byte.
     */
    private static String base64Url(byte[] bytes) {
        byte[] stripped = bytes;
        if (bytes.length > 1 && bytes[0] == 0) {
            stripped = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, stripped, 0, stripped.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(stripped);
    }
}

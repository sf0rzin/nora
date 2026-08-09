package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.identity.UserStatus;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RsaJwtIssuerTest {

    private static String privateKeyPem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        privateKeyPem = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
    }

    private User newUser() {
        Instant now = Instant.parse("2026-05-20T18:00:00Z");
        return new User(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Email.of("rs@nora.dev"),
                "h:irrelevant",
                "RS User",
                UserStatus.ACTIVE,
                now,
                now,
                now);
    }

    @Test
    void issueAndParseRoundTrip() {
        RsaJwtIssuer issuer = new RsaJwtIssuer(privateKeyPem, "nora-key-test");
        User user = newUser();

        String token = issuer.issue(user, List.of("ADMIN"), Duration.ofMinutes(15));
        assertThat(token.split("\\.")).hasSize(3);

        JjwtJwtIssuer.AuthenticatedPrincipal principal = issuer.parse(token);
        assertThat(principal.userId()).isEqualTo(user.id());
        assertThat(principal.tenantId()).isEqualTo(user.tenantId());
        assertThat(principal.email()).isEqualTo(user.email().value());
        assertThat(principal.roles()).containsExactly("ADMIN");
    }

    @Test
    void parsedTokenIncludesKidInHeader() {
        RsaJwtIssuer issuer = new RsaJwtIssuer(privateKeyPem, "nora-test-kid");
        String token = issuer.issue(newUser(), List.of(), Duration.ofMinutes(15));

        // Decode header (part 0) without validating the signature to inspect kid.
        String headerB64 = token.split("\\.")[0];
        String headerJson = new String(Base64.getUrlDecoder().decode(headerB64));
        assertThat(headerJson).contains("\"kid\":\"nora-test-kid\"");
        assertThat(headerJson).contains("\"alg\":\"RS256\"");
    }

    @Test
    void rejectsMissingPrivateKey() {
        assertThatThrownBy(() -> new RsaJwtIssuer(null, "kid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("private-key-pem");
        assertThatThrownBy(() -> new RsaJwtIssuer("", "kid"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new RsaJwtIssuer("   ", "kid"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedPrivateKey() {
        assertThatThrownBy(() -> new RsaJwtIssuer("not-base64!!!", "kid"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void exposesPublicKeyAndKidForJwks() {
        RsaJwtIssuer issuer = new RsaJwtIssuer(privateKeyPem, "my-kid");
        assertThat(issuer.kid()).isEqualTo("my-kid");
        assertThat(issuer.publicKey()).isNotNull();
        assertThat(issuer.publicKey().getModulus()).isNotNull();
    }
}

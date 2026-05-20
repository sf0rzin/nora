package br.com.nora.api.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class JjwtJwtIssuerTest {

    private static final String INSECURE_DEFAULT = "change-me-please-min-32-chars-long-aaaa";
    private static final String GOOD_SECRET = "this-is-a-strong-secret-with-32+chars-XYZ123";

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JjwtJwtIssuer("short", "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be at least 32 bytes");
    }

    @Test
    void rejectsInsecureDefaultOutsideLocalOrTest() {
        assertThatThrownBy(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, "prod"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder padrao");
        assertThatThrownBy(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, "staging"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder padrao");
        assertThatThrownBy(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder padrao");
    }

    @Test
    void acceptsInsecureDefaultInLocalAndTestProfiles() {
        assertThatCode(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, "local"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, "test"))
                .doesNotThrowAnyException();
        assertThatCode(() -> new JjwtJwtIssuer(INSECURE_DEFAULT, "local,test"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsStrongSecretInAnyProfile() {
        assertThatCode(() -> new JjwtJwtIssuer(GOOD_SECRET, "prod")).doesNotThrowAnyException();
        assertThatCode(() -> new JjwtJwtIssuer(GOOD_SECRET, "local")).doesNotThrowAnyException();
    }
}

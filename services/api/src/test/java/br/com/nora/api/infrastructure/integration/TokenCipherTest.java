package br.com.nora.api.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    private static String key32() {
        byte[] raw = new byte[32];
        for (int i = 0; i < 32; i++) {
            raw[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(raw);
    }

    /** Key configured: the opt-in is irrelevant, so these pass {@code false}. */
    private static TokenCipher withKey() {
        return new TokenCipher(key32(), false);
    }

    /** No key, plaintext degradation explicitly accepted (the local-dev path of ADR 0031). */
    private static TokenCipher withoutKeyOptedIn() {
        return new TokenCipher("", true);
    }

    @Test
    void comChave_roundtripCifrado() {
        TokenCipher cipher = withKey();
        String stored = cipher.encrypt("ya29.token-super-secreto");
        assertThat(stored).startsWith("enc:v1:");
        assertThat(stored).doesNotContain("token-super-secreto");
        assertThat(cipher.decrypt(stored)).isEqualTo("ya29.token-super-secreto");
    }

    @Test
    void comChave_ivAleatorioPorValor() {
        TokenCipher cipher = withKey();
        assertThat(cipher.encrypt("mesmo")).isNotEqualTo(cipher.encrypt("mesmo"));
    }

    @Test
    void semChaveComOptIn_armazenaPlainComPrefixo() {
        TokenCipher cipher = withoutKeyOptedIn();
        String stored = cipher.encrypt("token-dev");
        assertThat(stored).isEqualTo("plain:token-dev");
        assertThat(cipher.decrypt(stored)).isEqualTo("token-dev");
    }

    @Test
    void semChaveSemOptIn_falhaNoBoot() {
        assertThatThrownBy(() -> new TokenCipher("", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORA_INTEGRATIONS_ENC_KEY")
                .hasMessageContaining("NORA_INTEGRATIONS_ALLOW_PLAINTEXT");
    }

    @Test
    void chaveEmBrancoSemOptIn_falhaNoBoot() {
        assertThatThrownBy(() -> new TokenCipher("   ", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORA_INTEGRATIONS_ENC_KEY");
    }

    @Test
    void comChave_aindaLePlainLegado() {
        assertThat(withKey().decrypt("plain:antigo")).isEqualTo("antigo");
    }

    @Test
    void semChave_naoDecifraValorCifrado() {
        String stored = withKey().encrypt("x");
        TokenCipher semChave = withoutKeyOptedIn();
        assertThatThrownBy(() -> semChave.decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chaveComTamanhoErradoFalhaNoBoot() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new TokenCipher(key16, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    /** The opt-in does not weaken key validation: a malformed key is still fatal. */
    @Test
    void chaveInvalidaFalhaMesmoComOptIn() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new TokenCipher(key16, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void nuloPassaDireto() {
        TokenCipher cipher = withKey();
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}

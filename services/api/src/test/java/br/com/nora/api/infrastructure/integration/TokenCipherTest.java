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

    @Test
    void comChave_roundtripCifrado() {
        TokenCipher cipher = new TokenCipher(key32());
        String stored = cipher.encrypt("ya29.token-super-secreto");
        assertThat(stored).startsWith("enc:v1:");
        assertThat(stored).doesNotContain("token-super-secreto");
        assertThat(cipher.decrypt(stored)).isEqualTo("ya29.token-super-secreto");
    }

    @Test
    void comChave_ivAleatorioPorValor() {
        TokenCipher cipher = new TokenCipher(key32());
        assertThat(cipher.encrypt("mesmo")).isNotEqualTo(cipher.encrypt("mesmo"));
    }

    @Test
    void semChave_armazenaPlainComPrefixo() {
        TokenCipher cipher = new TokenCipher("");
        String stored = cipher.encrypt("token-dev");
        assertThat(stored).isEqualTo("plain:token-dev");
        assertThat(cipher.decrypt(stored)).isEqualTo("token-dev");
    }

    @Test
    void comChave_aindaLePlainLegado() {
        TokenCipher cipher = new TokenCipher(key32());
        assertThat(cipher.decrypt("plain:antigo")).isEqualTo("antigo");
    }

    @Test
    void semChave_naoDecifraValorCifrado() {
        TokenCipher comChave = new TokenCipher(key32());
        String stored = comChave.encrypt("x");
        TokenCipher semChave = new TokenCipher("");
        assertThatThrownBy(() -> semChave.decrypt(stored))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chaveComTamanhoErradoFalhaNoBoot() {
        String key16 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> new TokenCipher(key16))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void nuloPassaDireto() {
        TokenCipher cipher = new TokenCipher(key32());
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }
}

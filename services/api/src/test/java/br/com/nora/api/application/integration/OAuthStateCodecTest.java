package br.com.nora.api.application.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.domain.integration.IntegrationProvider;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthStateCodecTest {

    private final OAuthStateCodec codec = new OAuthStateCodec("segredo-de-teste-32-bytes-ok!!");
    private final Instant now = Instant.parse("2026-06-11T12:00:00Z");

    @Test
    void roundtrip_codificaEDecodifica() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String state = codec.encode(tenantId, userId, IntegrationProvider.GOOGLE, now);

        OAuthStateCodec.DecodedState decoded = codec.decode(state, now.plusSeconds(60));
        assertThat(decoded.tenantId()).isEqualTo(tenantId);
        assertThat(decoded.userId()).isEqualTo(userId);
        assertThat(decoded.provider()).isEqualTo(IntegrationProvider.GOOGLE);
    }

    @Test
    void rejeitaExpirado() {
        String state =
                codec.encode(UUID.randomUUID(), UUID.randomUUID(), IntegrationProvider.GOOGLE, now);
        assertThatThrownBy(() -> codec.decode(state, now.plusSeconds(601)))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void rejeitaAssinaturaAdulterada() {
        String state =
                codec.encode(UUID.randomUUID(), UUID.randomUUID(), IntegrationProvider.GOOGLE, now);
        String tampered = state.substring(0, state.length() - 4) + "XXXX";
        assertThatThrownBy(() -> codec.decode(tampered, now))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void rejeitaPayloadAdulterado() {
        String state =
                codec.encode(UUID.randomUUID(), UUID.randomUUID(), IntegrationProvider.GOOGLE, now);
        String[] parts = state.split(":");
        String tampered = "A" + parts[0].substring(1) + ":" + parts[1];
        assertThatThrownBy(() -> codec.decode(tampered, now))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void rejeitaLixoENulo() {
        assertThatThrownBy(() -> codec.decode(null, now))
                .isInstanceOf(IntegrationException.InvalidState.class);
        assertThatThrownBy(() -> codec.decode("", now))
                .isInstanceOf(IntegrationException.InvalidState.class);
        assertThatThrownBy(() -> codec.decode("lixo-sem-formato", now))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }

    @Test
    void segredosDiferentesNaoValidamCruzado() {
        OAuthStateCodec outro = new OAuthStateCodec("outro-segredo-completamente-diferente");
        String state =
                codec.encode(UUID.randomUUID(), UUID.randomUUID(), IntegrationProvider.GOOGLE, now);
        assertThatThrownBy(() -> outro.decode(state, now))
                .isInstanceOf(IntegrationException.InvalidState.class);
    }
}

package br.com.nora.api.api.dto.stt;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.stt.RealtimeSttSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The response DTO carries a live credential. A record's generated {@code toString} would print it,
 * so the override is load-bearing rather than cosmetic — and a component added to the record later
 * would silently restore the generated form. This test is what stops that.
 */
class SttSessionResponseTest {

    private static final RealtimeSttSession SESSION =
            new RealtimeSttSession(
                    "ek_supersecret",
                    Instant.parse("2026-08-17T12:00:00Z"),
                    "wss://provider.example/realtime",
                    "openai",
                    "gpt-live-transcribe",
                    "pt",
                    "audio/pcm",
                    24_000);

    @Test
    void carriesEveryFieldTheClientNeedsToStreamMatchingAudio() {
        SttSessionResponse response = SttSessionResponse.from(SESSION);

        assertThat(response.clientSecret()).isEqualTo("ek_supersecret");
        assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-08-17T12:00:00Z"));
        assertThat(response.websocketUrl()).isEqualTo("wss://provider.example/realtime");
        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.model()).isEqualTo("gpt-live-transcribe");
        assertThat(response.language()).isEqualTo("pt");
        assertThat(response.audioFormat()).isEqualTo("audio/pcm");
        assertThat(response.sampleRate()).isEqualTo(24_000);
    }

    @Test
    void neverPrintsTheCredential() {
        assertThat(SttSessionResponse.from(SESSION).toString())
                .doesNotContain("ek_supersecret")
                .contains("<redacted>");
    }
}

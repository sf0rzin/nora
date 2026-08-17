package br.com.nora.api.api.dto.stt;

import br.com.nora.api.application.stt.RealtimeSttSession;
import java.time.Instant;

/**
 * Everything the desktop needs to open one transcription stream, and nothing else. The account key
 * is not here, is not derivable from anything here, and is the reason this endpoint exists at all
 * (ADR 0039 §Decision 3).
 *
 * <p>{@code toString} is redacted for the same reason it is on {@link RealtimeSttSession}: a
 * generated record {@code toString} would publish the client secret through any log statement that
 * happened to interpolate the response.
 *
 * @param clientSecret short-lived credential presented to the provider over the WebSocket
 * @param expiresAt when the credential can no longer OPEN a connection. An already-open session
 *     outlives it, so the client must not build a renewal loop around this value (ADR 0045 §3)
 * @param websocketUrl provider endpoint to connect to, resolved server-side
 * @param provider provider id, for the client's error messages
 * @param model transcription model the session was created with
 * @param language primary language subtag actually sent to the provider
 * @param audioFormat provider media type of the PCM to stream
 * @param sampleRate sample rate in Hz the client must send; it matches the capture pipeline's
 *     target
 */
public record SttSessionResponse(
        String clientSecret,
        Instant expiresAt,
        String websocketUrl,
        String provider,
        String model,
        String language,
        String audioFormat,
        int sampleRate) {

    public static SttSessionResponse from(RealtimeSttSession session) {
        return new SttSessionResponse(
                session.clientSecret(),
                session.expiresAt(),
                session.websocketUrl(),
                session.provider(),
                session.model(),
                session.language(),
                session.audioFormat(),
                session.sampleRate());
    }

    @Override
    public String toString() {
        return "SttSessionResponse[clientSecret=<redacted>, expiresAt="
                + expiresAt
                + ", websocketUrl="
                + websocketUrl
                + ", provider="
                + provider
                + ", model="
                + model
                + ", language="
                + language
                + ", audioFormat="
                + audioFormat
                + ", sampleRate="
                + sampleRate
                + "]";
    }
}

package br.com.nora.api.application.stt;

import java.time.Instant;

/**
 * One realtime transcription session minted for a desktop client (ADR 0039, ADR 0045).
 *
 * <p>{@code clientSecret} is the whole point of the design and the whole risk of it: it is a
 * short-lived credential that reaches a paid provider, and the long-lived account key it was
 * exchanged for never leaves this process. The client secret therefore travels exactly twice — out
 * of the broker into the HTTP response — and must never reach a log, a metric label or an exception
 * message.
 *
 * <p>Which is why {@link #toString()} is overridden. A record's generated {@code toString} prints
 * every component, so a single {@code log.debug("minted {}", session)} would publish the credential
 * to whatever ships the logs. The override is covered by a test, because a future component added
 * to this record would otherwise silently re-enter the generated form.
 *
 * @param clientSecret the short-lived credential the client presents to the provider
 * @param expiresAt when the credential stops being usable to OPEN a connection — an already-open
 *     session is not killed by this instant, so there is no renewal loop (ADR 0045 §3)
 * @param websocketUrl where the client connects; resolved server-side so that changing provider
 *     endpoint is a configuration change rather than a desktop release
 * @param provider provider id, for telemetry and for the client's error messages
 * @param model the transcription model the session was created with
 * @param language BCP-47 primary subtag actually sent to the provider
 * @param audioFormat provider media type of the PCM the client will stream
 * @param sampleRate sample rate, in Hz, the provider expects and the capture pipeline targets
 */
public record RealtimeSttSession(
        String clientSecret,
        Instant expiresAt,
        String websocketUrl,
        String provider,
        String model,
        String language,
        String audioFormat,
        int sampleRate) {

    @Override
    public String toString() {
        return "RealtimeSttSession[clientSecret=<redacted>, expiresAt="
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

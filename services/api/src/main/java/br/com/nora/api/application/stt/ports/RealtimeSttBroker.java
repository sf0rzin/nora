package br.com.nora.api.application.stt.ports;

import br.com.nora.api.application.stt.RealtimeSttSession;

/**
 * Port for exchanging the server-held provider key for a short-lived session credential (ADR 0039
 * §Decision 3-4).
 *
 * <p>This is the shape the deleted {@code SpeechTokenBroker} had, rebuilt for a different vendor —
 * the pattern survived ADR 0035's deletion of Azure Speech, the vendor did not (ADR 0039 §"This
 * rebuilds a broker that was just deleted"). What changed is the return value: Azure's STS answered
 * with a bearer token plus a region, and a realtime session carries the endpoint, the model and the
 * audio format as well, because the client should not hardcode any of them.
 *
 * <p>Implementations live in {@code infrastructure} and are the only code in the API allowed to see
 * the provider account key.
 */
public interface RealtimeSttBroker {

    /**
     * Creates one transcription session with the provider and returns only its client secret.
     *
     * @param language BCP-47 primary subtag, already normalised by the application layer
     * @throws br.com.nora.api.application.stt.SttException when the provider is not configured or
     *     refuses
     */
    RealtimeSttSession openSession(String language);

    /** Provider id used in cost telemetry, e.g. {@code openai}. */
    String provider();

    /** Transcription model this broker is configured to request. */
    String model();
}

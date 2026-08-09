package br.com.nora.api.infrastructure.speech;

import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.ports.SpeechTokenBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op adapter of the {@code SpeechTokenBroker} port for local STT (ADR 0035 — Whisper embedded in
 * Tauri, transcribing on the client machine; successor to ADR 0009).
 *
 * <p>FINAL STATE vs. THIS STEP. ADR 0035 §"Impact on the code" says to delete {@code
 * SpeechController}, {@code SpeechTokenService}, {@code AzureSpeechTokenBroker}, the rate limit and
 * the whole {@code nora.speech.*} block. This class does NOT contradict that: it is the
 * transitional step that makes that deletion safe. While there are old desktops in the field
 * calling {@code /speech/token}, the route needs to answer something interpretable; when there are
 * none, it all goes away together.
 *
 * <p>WHY THE PORT STILL EXISTS AT THIS STEP, instead of deleting broker, service, controller and
 * DTO all at once:
 *
 * <ol>
 *   <li><b>Old clients in the field.</b> The already-shipped desktop calls {@code POST
 *       /speech/token} when booting voice capture. Making the route disappear would return Spring's
 *       generic 404, which the old client cannot tell apart from "API is down" and turns into an
 *       infinite retry. With the port alive, the same route answers 410 GONE + {@code
 *       SPEECH_PROVIDER_GONE} — a terminal, readable signal that the new client uses to fall
 *       straight through to local Whisper.
 *   <li><b>Reversibility of the migration.</b> {@code nora.speech.provider=azure} puts {@code
 *       AzureSpeechTokenBroker} back without touching code. If local Whisper does not perform on
 *       some user's hardware, the rollback is an env var, not a revert.
 *   <li><b>The port costs nothing.</b> It is a one-method interface in the application layer; the
 *       coupling to Azure was all in the infrastructure adapter, which is exactly what this file
 *       replaces. Deleting the port along with it would throw away the abstraction that made the
 *       swap cheap.
 * </ol>
 *
 * <p>The definitive deletion of {@code /speech/token} (and of the rest of the block, as ADR 0035
 * mandates) waits until telemetry shows zero calls for a whole retention window.
 *
 * <p>Note on the rate limit: {@code SpeechTokenService} consumes the {@code SpeechRateLimiter}
 * BEFORE calling the broker. That is intentional — an old client in a retry loop stays limited to 6
 * req/min per user, and the 410 does not become a free endpoint to hammer.
 */
@Component
@ConditionalOnProperty(name = "nora.speech.provider", havingValue = "local", matchIfMissing = true)
public class LocalSttNoopBroker implements SpeechTokenBroker {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSttNoopBroker.class);

    @Override
    public SpeechToken issue(String region) {
        LOG.debug(
                "Speech: /speech/token chamado com provider=local (region={}) — respondendo 410;"
                        + " cliente deve usar STT local.",
                region);
        throw new SpeechException.ProviderGone();
    }
}

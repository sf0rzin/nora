package br.com.nora.api.infrastructure.stt;

import br.com.nora.api.application.stt.RealtimeSttSession;
import br.com.nora.api.application.stt.SttException;
import br.com.nora.api.application.stt.ports.RealtimeSttBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Exchanges the server-held OpenAI account key for one ephemeral realtime transcription credential
 * (ADR 0039 §Decision 4, ADR 0045).
 *
 * <p>This class is the only place in the API that reads {@code nora.stt.openai.api-key}. The key
 * travels in an {@code Authorization} header to the provider and nowhere else: it is not in the
 * response, not in a log line, and not in the message of any exception thrown from here. The
 * provider's error body is not echoed either — only its status and its {@code error.message} field,
 * because a body copied wholesale is how a credential ends up in a support ticket.
 *
 * <p>The session is configured HERE rather than by the client, and that is the point of minting it
 * server-side: the model, the audio format, the sample rate and the VAD mode are cost and quality
 * decisions, and a desktop binary in the field must not be able to change them. The client is told
 * what was chosen, so it can stream matching audio.
 *
 * <p>Uses {@code java.net.http.HttpClient} rather than {@code WebClient}, following {@code
 * HttpEmbeddingClient}: one blocking call on a request thread, with an explicit timeout.
 *
 * <p>There is no {@code @ConditionalOnProperty} here, unlike the Azure broker this is modelled on.
 * That annotation existed to switch between two live vendors; there is one, and a conditional with
 * a single value is a mechanism that decides nothing.
 */
@Component
public class OpenAiRealtimeSessionBroker implements RealtimeSttBroker {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiRealtimeSessionBroker.class);

    /**
     * The GA path that mints a client secret. It replaced {@code /realtime/sessions} and {@code
     * /realtime/transcription_sessions}; the base is configurable so a further move does not need a
     * release.
     */
    private static final String CLIENT_SECRETS_PATH = "/realtime/client_secrets";

    private final SttProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiRealtimeSessionBroker(SttProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        long connectMs = Math.max(1_000L, props.openai().requestTimeoutMs() / 2L);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMs)).build();
    }

    @Override
    public String provider() {
        return "openai";
    }

    @Override
    public String model() {
        return props.openai().model();
    }

    @Override
    public RealtimeSttSession openSession(String language) {
        SttProperties.OpenAi cfg = props.openai();
        if (!cfg.isConfigured()) {
            throw new SttException.NotConfigured();
        }

        HttpResponse<String> response = send(cfg, language);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            LOG.warn("STT session mint refused: status={} providerSaid={}", status, hint(response));
            throw new SttException.BrokerError(
                    "The provider refused the session (HTTP " + status + ").");
        }

        JsonNode body = parse(response.body());
        String secret = body.path("value").asText("");
        if (secret.isBlank()) {
            throw new SttException.BrokerError(
                    "The provider returned a session with no credential.");
        }

        return new RealtimeSttSession(
                secret,
                expiryOf(body, cfg),
                cfg.realtimeUrl(),
                provider(),
                cfg.model(),
                language,
                cfg.audioFormat(),
                cfg.sampleRate());
    }

    private HttpResponse<String> send(SttProperties.OpenAi cfg, String language) {
        URI uri = URI.create(cfg.baseUrl() + CLIENT_SECRETS_PATH);
        HttpRequest request =
                HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(cfg.requestTimeoutMs()))
                        .header("Authorization", "Bearer " + cfg.apiKey())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mintPayload(cfg, language)))
                        .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SttException.BrokerError("Interrupted while creating the STT session.");
        } catch (Exception e) {
            // Message only, never the stack: this is a transport failure, and the useful part of
            // it is one line. The exception can carry the URI; it can never carry the key.
            LOG.warn("STT session mint could not reach the provider: {}", e.toString());
            throw new SttException.BrokerError("Could not reach the transcription provider.");
        }
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new SttException.BrokerError("The provider returned an unreadable session.");
        }
    }

    /** Trusts the provider's own expiry, and falls back to the requested TTL when it omits one. */
    private Instant expiryOf(JsonNode body, SttProperties.OpenAi cfg) {
        long epochSeconds = body.path("expires_at").asLong(0L);
        if (epochSeconds > 0) {
            return Instant.ofEpochSecond(epochSeconds);
        }
        return Instant.now().plusSeconds(cfg.sessionTtlSeconds());
    }

    /**
     * Body of {@code POST /realtime/client_secrets}: how long the secret may be used to open a
     * connection, plus the transcription session it opens.
     */
    private String mintPayload(SttProperties.OpenAi cfg, String language) {
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode expiresAfter = payload.putObject("expires_after");
        expiresAfter.put("anchor", "created_at");
        expiresAfter.put("seconds", cfg.sessionTtlSeconds());

        ObjectNode session = payload.putObject("session");
        session.put("type", "transcription");
        ObjectNode input = session.putObject("audio").putObject("input");

        ObjectNode format = input.putObject("format");
        format.put("type", cfg.audioFormat());
        format.put("rate", cfg.sampleRate());

        ObjectNode transcription = input.putObject("transcription");
        transcription.put("model", cfg.model());
        if (!language.isBlank()) {
            transcription.put("language", language);
        }

        if (cfg.turnDetection().isBlank()) {
            input.putNull("turn_detection");
        } else {
            input.putObject("turn_detection").put("type", cfg.turnDetection());
        }

        return payload.toString();
    }

    /** Pulls only the provider's own error message out of a failure body, never the whole body. */
    private String hint(HttpResponse<String> response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return "<empty>";
        }
        try {
            String message = mapper.readTree(body).path("error").path("message").asText("");
            return message.isBlank() ? "<none>" : message;
        } catch (Exception e) {
            return "<unparseable>";
        }
    }
}

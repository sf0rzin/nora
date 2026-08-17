package br.com.nora.api.infrastructure.stt;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.stt.RealtimeSttSession;
import br.com.nora.api.application.stt.SttException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The adapter that turns the account key into an ephemeral credential, and never the reverse. */
@WireMockTest
class OpenAiRealtimeSessionBrokerTest {

    private static final String KEY = "sk-test-account-key";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SttProperties props(String baseUrl, String key) {
        return new SttProperties(
                "pt",
                new SttProperties.OpenAi(
                        key,
                        baseUrl,
                        "wss://provider.example/realtime?intent=transcription",
                        "gpt-live-transcribe",
                        "audio/pcm",
                        24_000,
                        "server_vad",
                        600,
                        4_000),
                new SttProperties.RateLimit(12));
    }

    private static OpenAiRealtimeSessionBroker broker(String baseUrl, String key) {
        return new OpenAiRealtimeSessionBroker(props(baseUrl, key), MAPPER);
    }

    private static void stub(WireMock wireMock, int status, String body) {
        wireMock.register(
                post(urlPathEqualTo("/v1/realtime/client_secrets"))
                        .willReturn(
                                aResponse()
                                        .withStatus(status)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    @Test
    void withoutACredentialItFailsVisiblyInsteadOfReturningNothing() {
        assertThatThrownBy(() -> broker("https://unused.example/v1", "  ").openSession("pt"))
                .isInstanceOf(SttException.NotConfigured.class);
    }

    @Test
    void mintsASessionAndReturnsOnlyTheEphemeralCredential(WireMockRuntimeInfo wm) {
        stub(
                wm.getWireMock(),
                200,
                """
                {"value":"ek_ephemeral","expires_at":1786000000,
                 "session":{"type":"transcription","id":"sess_abc"}}
                """);

        RealtimeSttSession session = broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt");

        assertThat(session.clientSecret()).isEqualTo("ek_ephemeral");
        assertThat(session.expiresAt()).isEqualTo(Instant.ofEpochSecond(1786000000L));
        assertThat(session.websocketUrl())
                .isEqualTo("wss://provider.example/realtime?intent=transcription");
        assertThat(session.provider()).isEqualTo("openai");
        assertThat(session.model()).isEqualTo("gpt-live-transcribe");
        assertThat(session.language()).isEqualTo("pt");
        assertThat(session.audioFormat()).isEqualTo("audio/pcm");
        assertThat(session.sampleRate()).isEqualTo(24_000);
    }

    /**
     * The account key authenticates the mint call and appears nowhere else — not in the body that
     * describes the session, and above all not in the value handed back to the client.
     */
    @Test
    void theAccountKeyAuthenticatesTheCallAndLeavesNoOtherTrace(WireMockRuntimeInfo wm)
            throws Exception {
        stub(wm.getWireMock(), 200, "{\"value\":\"ek_ephemeral\",\"expires_at\":1786000000}");

        RealtimeSttSession session = broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt");

        List<LoggedRequest> requests =
                wm.getWireMock()
                        .find(
                                postRequestedFor(urlPathEqualTo("/v1/realtime/client_secrets"))
                                        .withHeader("Authorization", equalTo("Bearer " + KEY)));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getBodyAsString()).doesNotContain(KEY);
        assertThat(session.clientSecret()).doesNotContain(KEY);
        assertThat(session.toString()).doesNotContain("ek_ephemeral").doesNotContain(KEY);
    }

    /**
     * The session is configured server-side, which is the reason it is minted here at all: model,
     * audio format, sample rate and VAD are cost and quality decisions a desktop binary in the
     * field must not be able to change.
     */
    @Test
    void configuresTheSessionServerSide(WireMockRuntimeInfo wm) throws Exception {
        stub(wm.getWireMock(), 200, "{\"value\":\"ek_ephemeral\",\"expires_at\":1786000000}");

        broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt");

        LoggedRequest request =
                wm.getWireMock()
                        .find(postRequestedFor(urlPathEqualTo("/v1/realtime/client_secrets")))
                        .get(0);
        JsonNode body = MAPPER.readTree(request.getBodyAsString());

        assertThat(body.path("expires_after").path("anchor").asText()).isEqualTo("created_at");
        assertThat(body.path("expires_after").path("seconds").asInt()).isEqualTo(600);

        JsonNode input = body.path("session").path("audio").path("input");
        assertThat(body.path("session").path("type").asText()).isEqualTo("transcription");
        assertThat(input.path("format").path("type").asText()).isEqualTo("audio/pcm");
        assertThat(input.path("format").path("rate").asInt()).isEqualTo(24_000);
        assertThat(input.path("transcription").path("model").asText())
                .isEqualTo("gpt-live-transcribe");
        assertThat(input.path("transcription").path("language").asText()).isEqualTo("pt");
        assertThat(input.path("turn_detection").path("type").asText()).isEqualTo("server_vad");
    }

    /**
     * A refusal becomes STT_BROKER_ERROR, and the provider's body is NOT echoed: an upstream error
     * body copied wholesale into our error message is how a credential ends up in a support ticket.
     */
    @Test
    void aProviderRefusalDoesNotEchoTheProviderBody(WireMockRuntimeInfo wm) {
        stub(
                wm.getWireMock(),
                401,
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-test-account-key\"}}");

        assertThatThrownBy(() -> broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt"))
                .isInstanceOf(SttException.BrokerError.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining(KEY);
    }

    /** A 200 with no credential in it is a failure, not an empty session. */
    @Test
    void aResponseWithoutACredentialIsAFailure(WireMockRuntimeInfo wm) {
        stub(wm.getWireMock(), 200, "{\"expires_at\":1786000000}");

        assertThatThrownBy(() -> broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt"))
                .isInstanceOf(SttException.BrokerError.class);
    }

    /**
     * The provider may omit {@code expires_at}. Falling back to the configured TTL keeps the client
     * from treating the credential as immediately expired.
     */
    @Test
    void fallsBackToTheConfiguredTtlWhenTheProviderOmitsTheExpiry(WireMockRuntimeInfo wm) {
        stub(wm.getWireMock(), 200, "{\"value\":\"ek_ephemeral\"}");

        RealtimeSttSession session = broker(wm.getHttpBaseUrl() + "/v1", KEY).openSession("pt");

        assertThat(session.expiresAt()).isAfter(Instant.now().plusSeconds(500));
    }
}

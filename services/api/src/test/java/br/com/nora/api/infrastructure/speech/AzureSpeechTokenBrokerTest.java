package br.com.nora.api.infrastructure.speech;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechToken;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@WireMockTest
class AzureSpeechTokenBrokerTest {

    private AzureSpeechTokenBroker broker;
    private Clock clock;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmRuntimeInfo) {
        clock = () -> Instant.parse("2026-05-08T12:00:00Z");
        SpeechProperties props =
                new SpeechProperties(
                        new SpeechProperties.Azure(
                                "test-subscription-key",
                                "brazilsouth",
                                wmRuntimeInfo.getHttpBaseUrl() + "/sts/v1.0/issueToken",
                                540,
                                5000),
                        new SpeechProperties.RateLimit(6));

        broker = new AzureSpeechTokenBroker(props, RestClient.builder(), clock);
    }

    @Test
    void shouldIssueTokenSuccessfully(WireMockRuntimeInfo wmRuntimeInfo) {
        com.github.tomakehurst.wiremock.client.WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(
                post(urlEqualTo("/sts/v1.0/issueToken"))
                        .withHeader("Ocp-Apim-Subscription-Key", equalTo("test-subscription-key"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/jwt")
                                        .withBody("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.fake")));

        SpeechToken token = broker.issue("brazilsouth");

        assertThat(token.token()).isEqualTo("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.fake");
        assertThat(token.region()).isEqualTo("brazilsouth");
        assertThat(token.expiresAt()).isEqualTo(Instant.parse("2026-05-08T12:09:00Z"));

        wireMock.verifyThat(
                postRequestedFor(urlEqualTo("/sts/v1.0/issueToken"))
                        .withHeader("Ocp-Apim-Subscription-Key", equalTo("test-subscription-key")));
    }

    @Test
    void shouldThrowOnAzureError(WireMockRuntimeInfo wmRuntimeInfo) {
        com.github.tomakehurst.wiremock.client.WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(
                post(urlEqualTo("/sts/v1.0/issueToken"))
                        .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        assertThatThrownBy(() -> broker.issue("brazilsouth"))
                .isInstanceOf(SpeechException.BrokerError.class)
                .hasMessageContaining("401");
    }

    @Test
    void shouldThrowOnEmptyResponse(WireMockRuntimeInfo wmRuntimeInfo) {
        com.github.tomakehurst.wiremock.client.WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(
                post(urlEqualTo("/sts/v1.0/issueToken"))
                        .willReturn(aResponse().withStatus(200).withBody("")));

        assertThatThrownBy(() -> broker.issue("brazilsouth"))
                .isInstanceOf(SpeechException.BrokerError.class)
                .hasMessageContaining("empty token");
    }
}

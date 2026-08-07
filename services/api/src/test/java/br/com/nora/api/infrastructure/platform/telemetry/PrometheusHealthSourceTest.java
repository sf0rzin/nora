package br.com.nora.api.infrastructure.platform.telemetry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import br.com.nora.api.domain.platform.HealthSnapshot;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

@WireMockTest
class PrometheusHealthSourceTest {

    private static final String COUNT = "http_server_request_duration_seconds_count";
    private static final String BUCKET = "http_server_request_duration_seconds_bucket";

    private static PlatformProperties props(String prometheusUrl, String window) {
        PlatformProperties p = new PlatformProperties();
        p.getHealth().setPrometheusUrl(prometheusUrl);
        p.getHealth().setWindow(window);
        return p;
    }

    private static PrometheusHealthSource source(PlatformProperties p) {
        return new PrometheusHealthSource(p, WebClient.builder());
    }

    private static String vector(String job, String value) {
        return """
               {"status":"success","data":{"resultType":"vector","result":[
                 {"metric":{"job":"%s"},"value":[1780000000.000,"%s"]}]}}
               """
                .formatted(job, value);
    }

    /** Casa a query pelo trecho de PromQL que a diferencia das outras duas. */
    private static void stub(WireMock wireMock, String promqlFragment, String body) {
        wireMock.register(
                get(urlPathEqualTo("/api/v1/query"))
                        .withQueryParam("query", containing(promqlFragment))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(body)));
    }

    @Test
    void shouldBeUnavailableWhenNotConfigured() {
        HealthSnapshot snapshot = source(props("", "PT1H")).fetch();

        assertThat(snapshot.source()).isEqualTo("unavailable");
        assertThat(snapshot.window()).isEqualTo("PT1H");
        assertThat(snapshot.services()).isEmpty();
        assertThat(snapshot.degraded()).isFalse();
        assertThat(snapshot.note()).contains("NORA_PLATFORM_HEALTH_PROMETHEUS_URL");
    }

    @Test
    void shouldAggregateRequestsFailuresAndP95(WireMockRuntimeInfo wm) {
        WireMock wireMock = wm.getWireMock();
        stub(wireMock, "increase(" + COUNT + "[3600s])", vector("nora-api", "1840"));
        stub(wireMock, "http_response_status_code", vector("nora-api", "12"));
        stub(wireMock, BUCKET, vector("nora-api", "0.412"));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT1H")).fetch();

        assertThat(snapshot.source()).isEqualTo("prometheus");
        assertThat(snapshot.window()).isEqualTo("PT1H");
        assertThat(snapshot.degraded()).isFalse();
        assertThat(snapshot.services()).hasSize(1);

        HealthSnapshot.ServiceHealth api = snapshot.services().get(0);
        assertThat(api.role()).isEqualTo("nora-api");
        assertThat(api.requests()).isEqualTo(1840L);
        assertThat(api.failed()).isEqualTo(12L);
        assertThat(api.failureRate()).isCloseTo(12d / 1840d, within(1e-9));
        // Semconv estável do OTel mede em SEGUNDOS; o contrato do painel é em MILISSEGUNDOS.
        assertThat(api.p95LatencyMs()).isCloseTo(412.0, within(1e-6));
    }

    @Test
    void shouldTranslateWindowToPromqlRange(WireMockRuntimeInfo wm) {
        WireMock wireMock = wm.getWireMock();
        stub(wireMock, "increase(" + COUNT + "[900s])", vector("nora-api", "10"));
        stub(wireMock, "http_response_status_code", vector("nora-api", "0"));
        stub(wireMock, BUCKET, vector("nora-api", "0.1"));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT15M")).fetch();

        assertThat(snapshot.source()).isEqualTo("prometheus");
        assertThat(snapshot.window()).isEqualTo("PT15M");
        wireMock.verifyThat(
                getRequestedFor(urlPathEqualTo("/api/v1/query"))
                        .withQueryParam("query", containing("[900s]")));
    }

    @Test
    void shouldFlagDegradedAboveFivePercentFailureRate(WireMockRuntimeInfo wm) {
        WireMock wireMock = wm.getWireMock();
        stub(wireMock, "increase(" + COUNT + "[3600s])", vector("nora-worker", "100"));
        stub(wireMock, "http_response_status_code", vector("nora-worker", "6"));
        stub(wireMock, BUCKET, vector("nora-worker", "8.45"));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT1H")).fetch();

        assertThat(snapshot.degraded()).isTrue();
        assertThat(snapshot.services().get(0).failureRate()).isGreaterThan(0.05);
    }

    @Test
    void shouldReturnNullP95WhenQuantileIsNaN(WireMockRuntimeInfo wm) {
        WireMock wireMock = wm.getWireMock();
        stub(wireMock, "increase(" + COUNT + "[3600s])", vector("nora-api", "5"));
        stub(wireMock, "http_response_status_code", vector("nora-api", "0"));
        stub(wireMock, BUCKET, vector("nora-api", "NaN"));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT1H")).fetch();

        assertThat(snapshot.services()).hasSize(1);
        assertThat(snapshot.services().get(0).p95LatencyMs()).isNull();
    }

    @Test
    void shouldReturnEmptyServicesWhenNoSeriesYet(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/api/v1/query"))
                                .willReturn(
                                        aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody(
                                                        "{\"status\":\"success\",\"data\":"
                                                                + "{\"resultType\":\"vector\","
                                                                + "\"result\":[]}}")));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT1H")).fetch();

        assertThat(snapshot.source()).isEqualTo("prometheus");
        assertThat(snapshot.services()).isEmpty();
        assertThat(snapshot.degraded()).isFalse();
    }

    @Test
    void shouldDegradeToUnavailableOnPrometheusError(WireMockRuntimeInfo wm) {
        wm.getWireMock()
                .register(
                        get(urlPathEqualTo("/api/v1/query"))
                                .willReturn(aResponse().withStatus(503).withBody("down")));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "PT1H")).fetch();

        assertThat(snapshot.source()).isEqualTo("unavailable");
        assertThat(snapshot.services()).isEmpty();
        assertThat(snapshot.note()).contains("Prometheus");
    }

    @Test
    void shouldFallBackToDefaultWindowWhenInvalid(WireMockRuntimeInfo wm) {
        WireMock wireMock = wm.getWireMock();
        stub(wireMock, "increase(" + COUNT + "[3600s])", vector("nora-api", "1"));
        stub(wireMock, "http_response_status_code", vector("nora-api", "0"));
        stub(wireMock, BUCKET, vector("nora-api", "0.2"));

        HealthSnapshot snapshot = source(props(wm.getHttpBaseUrl(), "uma-hora")).fetch();

        assertThat(snapshot.window()).isEqualTo("PT1H");
        assertThat(snapshot.source()).isEqualTo("prometheus");
    }
}

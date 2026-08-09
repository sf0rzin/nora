package br.com.nora.api.infrastructure.platform.telemetry;

import br.com.nora.api.application.platform.HealthMetricsSource;
import br.com.nora.api.domain.platform.HealthSnapshot;
import br.com.nora.api.domain.platform.HealthSnapshot.ServiceHealth;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;

/**
 * Reads system health via the Prometheus HTTP query API (telemetry (b), ADR 0024/0034).
 *
 * <p>Replaces {@code AppInsightsHealthSource}, which did a GET on {@code
 * api.applicationinsights.io/v1/apps/{id}/query} with KQL. An OTel Collector is write-only and does
 * not speak KQL, so the READ leg does not migrate by swapping an env var: the three KQL
 * aggregations became three PromQL instant queries against the series the opentelemetry-javaagent
 * publishes.
 *
 * <p>Translation of the original KQL — {@code requests | summarize requests=count(),
 * failed=countif(success=='False'), p95=percentile(duration,95) by cloud_RoleName}:
 *
 * <ul>
 *   <li>the {@code requests} table → the agent's {@code http.server.request.duration} histogram
 *       (server-side), exposed in Prometheus as {@code http_server_request_duration_seconds_*}.
 *   <li>{@code count()} → {@code increase(..._count[window])}.
 *   <li>{@code countif(success=='False')} → the same counter filtered by {@code
 *       http_response_status_code=~"[45].."}. App Insights marks a request as failed when the
 *       responseCode is &gt;= 400, so 4xx counts — keeping that preserves the number the dashboard
 *       already showed.
 *   <li>{@code percentile(duration,95)} → {@code histogram_quantile(0.95, ..._bucket)}. WATCH the
 *       unit: KQL returned MILLISECONDS and OTel's stable semconv uses SECONDS — hence the {@code *
 *       1000} before filling {@code p95LatencyMs}.
 *   <li>{@code by cloud_RoleName} → {@code by (job)}. The collector's prometheusremotewrite
 *       exporter maps the {@code service.name} resource to the {@code job} label; the values are
 *       the same OTEL_SERVICE_NAME from compose (nora-api, nora-worker, nora-web, nora-admin).
 * </ul>
 *
 * <p>Best-effort, same as the previous adapter: with no {@code prometheus-url} configured, or if
 * the query fails, it returns {@link HealthSnapshot#unavailable} (the /admin/.../health endpoint
 * still answers 200). Bean always present (not gated) — does not touch the platform database.
 */
@Component
public class PrometheusHealthSource implements HealthMetricsSource {

    private static final Logger LOG = LoggerFactory.getLogger(PrometheusHealthSource.class);

    /** Service identity label in Prometheus (equivalent to cloud_RoleName). */
    private static final String ROLE_LABEL = "job";

    private static final String METRIC = "http_server_request_duration_seconds";

    private static final String Q_REQUESTS =
            "sum by (" + ROLE_LABEL + ") (increase(" + METRIC + "_count[%s]))";
    private static final String Q_FAILED =
            "sum by ("
                    + ROLE_LABEL
                    + ") (increase("
                    + METRIC
                    + "_count{http_response_status_code=~\"[45]..\"}[%s]))";
    private static final String Q_P95 =
            "histogram_quantile(0.95, sum by ("
                    + ROLE_LABEL
                    + ", le) (increase("
                    + METRIC
                    + "_bucket[%s])))";

    /** Same total budget as the previous adapter; the 3 queries go in parallel. */
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static final String DEFAULT_WINDOW = "PT1H";

    private final PlatformProperties props;
    private final WebClient webClient;

    public PrometheusHealthSource(PlatformProperties props, WebClient.Builder builder) {
        this.props = props;
        this.webClient = builder.build();
    }

    @Override
    public HealthSnapshot fetch() {
        PlatformProperties.Health h = props.getHealth();
        String window =
                h.getWindow() == null || h.getWindow().isBlank() ? DEFAULT_WINDOW : h.getWindow();
        Duration parsed = parseWindow(window);
        if (parsed == null) {
            LOG.warn("Health: janela inválida '{}', caindo para {}", window, DEFAULT_WINDOW);
            window = DEFAULT_WINDOW;
            parsed = Duration.ofHours(1);
        }
        if (isBlank(h.getPrometheusUrl())) {
            return HealthSnapshot.unavailable(
                    window,
                    "Prometheus query API não configurada (NORA_PLATFORM_HEALTH_PROMETHEUS_URL)");
        }

        String base = trimTrailingSlash(h.getPrometheusUrl());
        String range = parsed.toSeconds() + "s";
        try {
            Tuple3<PromResponse, PromResponse, PromResponse> res =
                    Mono.zip(
                                    query(base, String.format(Q_REQUESTS, range)),
                                    query(base, String.format(Q_FAILED, range)),
                                    query(base, String.format(Q_P95, range)))
                            .block(TIMEOUT);
            return parse(res, window);
        } catch (Exception ex) {
            LOG.warn("Health: falha ao consultar Prometheus: {}", ex.getMessage());
            return HealthSnapshot.unavailable(window, "falha ao consultar Prometheus");
        }
    }

    private Mono<PromResponse> query(String base, String promql) {
        URI uri =
                URI.create(
                        base
                                + "/api/v1/query?query="
                                + URLEncoder.encode(promql, StandardCharsets.UTF_8));
        return webClient.get().uri(uri).retrieve().bodyToMono(PromResponse.class);
    }

    private HealthSnapshot parse(
            Tuple3<PromResponse, PromResponse, PromResponse> res, String window) {
        if (res == null) {
            return HealthSnapshot.unavailable(window, "resposta vazia do Prometheus");
        }
        if (malformed(res.getT1()) || malformed(res.getT2()) || malformed(res.getT3())) {
            return HealthSnapshot.unavailable(window, "resposta inválida do Prometheus");
        }

        Map<String, Double> requests = byRole(res.getT1());
        Map<String, Double> failed = byRole(res.getT2());
        Map<String, Double> p95Seconds = byRole(res.getT3());

        // Ordered union: a service may show up only in the p95 (e.g. counter zeroed by the reset).
        Set<String> roles = new LinkedHashSet<>(requests.keySet());
        roles.addAll(failed.keySet());
        roles.addAll(p95Seconds.keySet());

        List<ServiceHealth> services = new ArrayList<>();
        for (String role : roles) {
            long total = round(requests.get(role));
            long fail = round(failed.get(role));
            Double seconds = p95Seconds.get(role);
            Double p95Ms = seconds == null ? null : seconds * 1000.0;
            double rate = total > 0 ? (double) fail / total : 0.0;
            services.add(new ServiceHealth(role, total, fail, rate, p95Ms));
        }
        boolean degraded = services.stream().anyMatch(s -> s.failureRate() > 0.05);
        return new HealthSnapshot(window, "prometheus", services, degraded, null);
    }

    private static boolean malformed(PromResponse r) {
        return r == null
                || !"success".equals(r.status())
                || r.data() == null
                || r.data().result() == null;
    }

    private static Map<String, Double> byRole(PromResponse resp) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (PromResult r : resp.data().result()) {
            String role =
                    r.metric() == null ? null : r.metric().getOrDefault(ROLE_LABEL, "unknown");
            if (role == null || role.isBlank()) {
                role = "unknown";
            }
            Double value = scalar(r.value());
            if (value != null) {
                out.put(role, value);
            }
        }
        return out;
    }

    /** Prometheus' `value` field is [timestampEpochSeconds, "valueAsString"]. */
    private static Double scalar(List<Object> value) {
        if (value == null || value.size() < 2 || value.get(1) == null) {
            return null;
        }
        try {
            double d = Double.parseDouble(value.get(1).toString().trim());
            // "NaN" is the normal histogram_quantile answer when there are no samples.
            return Double.isFinite(d) ? d : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static long round(Double d) {
        return d == null ? 0L : Math.round(d);
    }

    private static Duration parseWindow(String window) {
        try {
            Duration d = Duration.parse(window);
            return d.isZero() || d.isNegative() ? null : d;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromResponse(String status, PromData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromData(String resultType, List<PromResult> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromResult(Map<String, String> metric, List<Object> value) {}
}

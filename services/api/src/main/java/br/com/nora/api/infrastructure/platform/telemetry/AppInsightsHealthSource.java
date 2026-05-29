package br.com.nora.api.infrastructure.platform.telemetry;

import br.com.nora.api.application.platform.HealthMetricsSource;
import br.com.nora.api.domain.platform.HealthSnapshot;
import br.com.nora.api.domain.platform.HealthSnapshot.ServiceHealth;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Lê saúde do sistema via Application Insights REST query API (telemetria (b), ADR 0024). Best-effort:
 * se {@code app-insights-app-id}/{@code api-key} não estiverem configurados, ou a consulta falhar,
 * devolve {@link HealthSnapshot#unavailable} (o endpoint /admin/.../health ainda responde 200). Bean
 * sempre presente (não-gated) — não toca o banco de plataforma.
 */
@Component
public class AppInsightsHealthSource implements HealthMetricsSource {

    private static final Logger LOG = LoggerFactory.getLogger(AppInsightsHealthSource.class);
    private static final String KQL =
            "requests | summarize requests=count(), failed=countif(success=='False'),"
                + " p95=percentile(duration,95) by cloud_RoleName";

    private final PlatformProperties props;
    private final WebClient webClient;

    public AppInsightsHealthSource(PlatformProperties props, WebClient.Builder builder) {
        this.props = props;
        this.webClient = builder.build();
    }

    @Override
    public HealthSnapshot fetch() {
        PlatformProperties.Health h = props.getHealth();
        String window = h.getWindow() == null || h.getWindow().isBlank() ? "PT1H" : h.getWindow();
        if (isBlank(h.getAppInsightsAppId()) || isBlank(h.getApiKey())) {
            return HealthSnapshot.unavailable(
                    window,
                    "App Insights query API não configurada"
                        + " (NORA_PLATFORM_HEALTH_APP_ID + NORA_PLATFORM_HEALTH_API_KEY)");
        }
        try {
            AiResponse resp =
                    webClient
                            .get()
                            .uri(
                                    b ->
                                            b.scheme("https")
                                                    .host("api.applicationinsights.io")
                                                    .path("/v1/apps/{appId}/query")
                                                    .queryParam("query", KQL)
                                                    .queryParam("timespan", window)
                                                    .build(h.getAppInsightsAppId()))
                            .header("x-api-key", h.getApiKey())
                            .retrieve()
                            .bodyToMono(AiResponse.class)
                            .block(Duration.ofSeconds(8));
            return parse(resp, window);
        } catch (Exception ex) {
            LOG.warn("Health: falha ao consultar App Insights: {}", ex.getMessage());
            return HealthSnapshot.unavailable(window, "falha ao consultar App Insights");
        }
    }

    private HealthSnapshot parse(AiResponse resp, String window) {
        if (resp == null || resp.tables() == null || resp.tables().isEmpty()) {
            return HealthSnapshot.unavailable(window, "resposta vazia do App Insights");
        }
        AiTable t = resp.tables().get(0);
        if (t.columns() == null || t.rows() == null) {
            return HealthSnapshot.unavailable(window, "tabela vazia do App Insights");
        }
        int iRole = indexOf(t.columns(), "cloud_RoleName");
        int iReq = indexOf(t.columns(), "requests");
        int iFail = indexOf(t.columns(), "failed");
        int iP95 = indexOf(t.columns(), "p95");
        List<ServiceHealth> services = new ArrayList<>();
        for (List<Object> row : t.rows()) {
            String role = iRole >= 0 ? str(row.get(iRole)) : "unknown";
            long requests = iReq >= 0 ? asLong(row.get(iReq)) : 0;
            long failed = iFail >= 0 ? asLong(row.get(iFail)) : 0;
            Double p95 = iP95 >= 0 ? asDouble(row.get(iP95)) : null;
            double rate = requests > 0 ? (double) failed / requests : 0.0;
            services.add(new ServiceHealth(role, requests, failed, rate, p95));
        }
        boolean degraded = services.stream().anyMatch(s -> s.failureRate() > 0.05);
        return new HealthSnapshot(window, "application-insights", services, degraded, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int indexOf(List<AiColumn> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (name.equals(cols.get(i).name())) {
                return i;
            }
        }
        return -1;
    }

    private static String str(Object o) {
        return o == null ? "unknown" : o.toString();
    }

    private static long asLong(Object o) {
        if (o instanceof Number num) {
            return num.longValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return (long) Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number num) {
            return num.doubleValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AiResponse(List<AiTable> tables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AiTable(String name, List<AiColumn> columns, List<List<Object>> rows) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AiColumn(String name, String type) {}
}

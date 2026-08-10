package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.platform.BusinessMetricsSource;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.AnalysisResult;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.platform.BusinessSnapshot;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proof that the app WORKS under REAL RLS enforce (ADR 0028) — not just isolation at the database
 * level (that is {@link RlsEnforcementIntegrationTest}), but the whole app running as the
 * NOBYPASSRLS role, end-to-end through the HTTP API.
 *
 * <p>Setup that mirrors the production cutover:
 *
 * <ul>
 *   <li>the runtime app connects as {@code nora_app_test} (NOBYPASSRLS → RLS really applies);
 *   <li>Flyway connects as the container owner/admin (DDL + owner of the tables);
 *   <li>{@code nora.security.rls.enforce=true} → the {@code TenantRlsAspect} sets the GUC per
 *       transaction.
 * </ul>
 *
 * <p>What this guarantees and the ITs with an owner connection would NOT:
 *
 * <ol>
 *   <li><b>Auth does not break</b> under enforce: signup/verify/login operate on IDENTITY tables
 *       that V020 exempted (otherwise fail-closed without GUC). ⬅️ the hole ADR 0026 missed.
 *   <li><b>Enforced tables work</b> for the authenticated tenant (upload creates
 *       meeting/transcript/ participants; list/detail read) — the aspect sets the GUC from the JWT.
 *   <li><b>Real isolation</b>: tenant B does NOT see A's meeting — now via Postgres, not just via
 *       the application filter.
 *   <li><b>Async pipeline</b> writes under enforce: the analysis (executor thread) completes,
 *       proving the TaskDecorator propagated the tenant to the GUC outside the request thread.
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(RlsAppEnforcementIntegrationTest.StubWorkerConfig.class)
class RlsAppEnforcementIntegrationTest {

    private static final String APP_ROLE = "nora_app_test";
    private static final String APP_PASSWORD = "nora_app_test_pwd";
    private static final String TELEMETRY_ROLE = "nora_telemetry_test";
    private static final String TELEMETRY_PASSWORD = "nora_telemetry_test_pwd";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        // Runtime = nora_app_test (NOBYPASSRLS, created in @BeforeAll). Flyway = admin (owner of
        // the container). Enforce ON. Suppliers lazy — evaluated at context-load, container up.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("nora.security.rls.enforce", () -> "true");
        // Under enforce the telemetry datasource is not optional: the operator console's
        // cross-tenant aggregate runs with no tenant GUC, so as nora_app it would read zero rows
        // with no error. RlsEnforceTelemetryGuard refuses to start without it, so this test has
        // to provide it — and providing it is also what makes the test resemble production.
        registry.add("nora.security.rls.telemetry.url", POSTGRES::getJdbcUrl);
        registry.add("nora.security.rls.telemetry.username", () -> TELEMETRY_ROLE);
        registry.add("nora.security.rls.telemetry.password", () -> TELEMETRY_PASSWORD);
        // The 'test' profile turns off the async dispatch (no worker in the ITs). We turn it on
        // here to exercise the async pipeline under enforce (with StubWorkerConfig as the worker).
        registry.add("nora.analysis.auto-dispatch", () -> "true");
    }

    @Autowired TestRestTemplate rest;
    @Autowired BusinessMetricsSource businessMetrics;
    @Autowired ObjectMapper mapper;

    /**
     * Creates the NOBYPASSRLS role BEFORE the Spring context comes up. {@code @BeforeAll} runs
     * after Testcontainers starts the container, but BEFORE the context-load
     * ({@code @DynamicPropertySource} + Flyway + API pool) — so the role already exists when the
     * runtime pool connects as it.
     */
    @BeforeAll
    static void createRuntimeRole() throws Exception {
        try (Connection c =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + APP_ROLE);
            s.execute(
                    "CREATE ROLE "
                            + APP_ROLE
                            + " WITH LOGIN PASSWORD '"
                            + APP_PASSWORD
                            + "' NOBYPASSRLS");
            // The third role of the cutover (ADR 0026/0028): BYPASSRLS, for the operator
            // console's cross-tenant aggregate. Production provisions it in R001.
            s.execute("DROP ROLE IF EXISTS " + TELEMETRY_ROLE);
            s.execute(
                    "CREATE ROLE "
                            + TELEMETRY_ROLE
                            + " WITH LOGIN PASSWORD '"
                            + TELEMETRY_PASSWORD
                            + "' BYPASSRLS");
        }
    }

    private static boolean granted = false;

    @BeforeEach
    void setUp() throws Exception {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        grantRuntimeRoleOnce();
    }

    /**
     * nora_app grants (mirrors R001) — they need the schema + the {@code nora.current_tenant_id()}
     * function, created by Flyway at context-load; they run here (post-load) once. The app boots
     * before that doing only a {@code SELECT 1} health check (without grant), so the pool comes up;
     * the grants arrive before the 1st API call.
     */
    private static synchronized void grantRuntimeRoleOnce() throws Exception {
        if (granted) {
            return;
        }
        try (Connection c =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            s.execute("GRANT USAGE ON SCHEMA nora TO " + APP_ROLE);
            s.execute("GRANT EXECUTE ON FUNCTION nora.current_tenant_id() TO " + APP_ROLE);
            s.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                            + APP_ROLE);
            s.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
            // nora_telemetry grants (mirrors R001): read only, and only what the aggregate needs.
            s.execute("GRANT USAGE ON SCHEMA public TO " + TELEMETRY_ROLE);
            s.execute("GRANT SELECT ON meeting_analyses TO " + TELEMETRY_ROLE);
        }
        granted = true;
    }

    @Test
    void authFlows_workUnderEnforce() throws Exception {
        // If V020 had not exempted the identity tables, this would fail (fail-closed).
        String token = signupAndLogin("auth-enforce@nora.dev", "SenhaForte123", "Auth Enforce");
        assertThat(token).isNotBlank();
        // Authenticated enforced read (empty list) works — the aspect set the GUC from the JWT.
        JsonNode list = authGet("/meetings", token).read(HttpStatus.OK);
        assertThat(list.get("totalItems").asInt()).isZero();
    }

    @Test
    void upload_listDetail_andCrossTenantIsolation_underEnforce() throws Exception {
        String aToken = signupAndLogin("tenant-a@nora.dev", "SenhaForte123", "Tenant A");
        String metadata =
                mapper.writeValueAsString(
                        Map.of(
                                "title",
                                "Reuniao secreta A",
                                "language",
                                "pt-BR",
                                "transcriptFormat",
                                "TXT",
                                "tags",
                                List.of("venda")));
        String content = "A: conteudo confidencial do tenant A.\nB: ok, combinado.";

        ResponseEntity<String> upload =
                multipartUpload(
                        "/meetings", aToken, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String meetingId = mapper.readTree(upload.getBody()).get("id").asText();

        // A sees it (write+read on an enforced table with A's GUC).
        JsonNode aList = authGet("/meetings", aToken).read(HttpStatus.OK);
        assertThat(aList.get("totalItems").asInt()).isEqualTo(1);
        JsonNode aDetail = authGet("/meetings/" + meetingId, aToken).read(HttpStatus.OK);
        assertThat(aDetail.get("title").asText()).isEqualTo("Reuniao secreta A");

        // B (another tenant) does NOT see it — isolation guaranteed by Postgres, not just the app.
        String bToken = signupAndLogin("tenant-b@nora.dev", "SenhaForte123", "Tenant B");
        ResponseEntity<String> bCross = authGetRaw("/meetings/" + meetingId, bToken);
        assertThat(bCross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode bList = authGet("/meetings", bToken).read(HttpStatus.OK);
        assertThat(bList.get("totalItems").asInt()).isZero();
    }

    @Test
    void asyncAnalysisPipeline_completesUnderEnforce() throws Exception {
        String token = signupAndLogin("analysis-enforce@nora.dev", "SenhaForte123", "Analysis");
        String metadata = "{\"title\":\"Analise sob enforce\",\"transcriptFormat\":\"TXT\"}";
        String content = "Joao: vamos fechar o contrato.\nMaria: perfeito, fechado.";

        ResponseEntity<String> upload =
                multipartUpload(
                        "/meetings", token, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String meetingId = mapper.readTree(upload.getBody()).get("id").asText();

        // The async pipeline (executor thread) writes meeting_analyses under enforce. If the
        // TaskDecorator did not propagate the tenant, the write would be fail-closed and the
        // status would never reach COMPLETED (it would stay PROCESSING or FAILED).
        String status = pollProcessingStatus(meetingId, token);
        assertThat(status).isEqualTo("COMPLETED");
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.has("analysis")).isTrue();

        // And now the half of enforce that fails SILENTLY. The operator console aggregates
        // meeting_analyses across tenants with no tenant GUC set. As nora_app that is
        // fail-closed, so without the BYPASSRLS telemetry datasource this returns zero and
        // nothing anywhere reports a problem — the dashboard just looks like a quiet week.
        // Provisioning nora_telemetry_test is not enough to prove that: it only proves the role
        // can log in. This asserts the aggregate actually sees the row that was just written.
        BusinessSnapshot snapshot =
                businessMetrics.fetch(
                        OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));
        assertThat(snapshot.enabled())
                .as("telemetry datasource must be active under enforce")
                .isTrue();
        assertThat(snapshot.analyses())
                .as("cross-tenant aggregate must see the analysis just written")
                .isGreaterThanOrEqualTo(1L);
    }

    /* ---------- helpers ---------- */

    private String pollProcessingStatus(String meetingId, String token) throws Exception {
        for (int i = 0; i < 40; i++) {
            JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
            String status = detail.get("processingStatus").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "TIMEOUT";
    }

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name))
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken)).read(HttpStatus.NO_CONTENT);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", pwd))
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private ResponseEntity<String> multipartUpload(
            String path, String token, String metadataJson, byte[] fileBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaH = new HttpHeaders();
        metaH.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(metadataJson, metaH));

        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource fileResource =
                new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return "transcript.txt";
                    }
                };
        body.add("file", new HttpEntity<>(fileResource, fileH));

        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private RequestExec postJson(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(authGetRaw(path, token));
    }

    private ResponseEntity<String> authGetRaw(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private final class RequestExec {
        final ResponseEntity<String> response;

        RequestExec(ResponseEntity<String> r) {
            this.response = r;
        }

        JsonNode read(HttpStatus expected) throws Exception {
            assertThat(response.getStatusCode())
                    .as("body=%s", response.getBody())
                    .isEqualTo(expected);
            return response.getBody() == null
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.getBody());
        }
    }

    /**
     * Deterministic stub of the NLP worker — there is no worker running in the IT. Makes the async
     * analysis complete (writing {@code meeting_analyses} under enforce), proving the TaskDecorator
     * propagated the tenant to the GUC on the executor thread. Nested @TestConfiguration
     * auto-detected by {@code @SpringBootTest}.
     */
    @TestConfiguration
    static class StubWorkerConfig {
        @Bean
        @Primary
        NlpWorkerClient stubWorker() {
            return new NlpWorkerClient() {
                @Override
                public AnalysisResult analyze(
                        UUID meetingId,
                        UUID tenantId,
                        String language,
                        String transcript,
                        Optional<TenantContext> tenantContext,
                        Optional<br.com.nora.api.domain.meeting.productivity.MeetingGoal> goal) {
                    MeetingAnalysis analysis =
                            MeetingAnalysis.newAnalysis(
                                    meetingId,
                                    tenantId,
                                    "Resumo stub (RLS enforce IT).",
                                    Sentiment.NEUTRAL,
                                    List.of("topico1"),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    "stub-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    0);
                    return AnalysisResult.of(analysis, null, null);
                }

                @Override
                public WorkerDtos.LiveAnalyzeResponse analyzeLive(
                        String transcriptChunk,
                        String language,
                        WorkerDtos.LiveHighlights previousHighlights) {
                    return new WorkerDtos.LiveAnalyzeResponse(
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            new WorkerDtos.LiveMetadata(0, 0, 0, 0, "stub-live"));
                }
            };
        }
    }
}

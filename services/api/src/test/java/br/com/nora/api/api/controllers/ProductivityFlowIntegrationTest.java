package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.meeting.productivity.CoverageStatus;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.OutcomeCoverage;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.meeting.productivity.ProductivityBand;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * End-to-end Productivity Score flow (ADR 0005): upload -> set goal -> run stub analysis -> GET
 * returns goal+productivity. Also covers per-tenant isolation and goal DELETE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(ProductivityFlowIntegrationTest.StubWorkerConfig.class)
class ProductivityFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired AnalysisService analysisService;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void setGoalAndAnalyzeProducesProductivity() throws Exception {
        String token = signupAndLogin("prod@nora.dev", "SenhaForte123", "Owner Prod");
        UUID meetingId = uploadMeeting(token, "Discovery Acme");

        // 1) Set goal (meeting still in PENDING).
        Map<String, Object> goalBody =
                Map.of(
                        "purpose",
                        "Discovery com lead Acme",
                        "expectedOutcomes",
                        List.of("Identificar dor principal", "Coletar criterios de decisao"),
                        "projectStateSnapshot",
                        "Lead em fase inicial");
        ResponseEntity<String> putResp = putGoal(meetingId, token, goalBody);
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode goalNode = mapper.readTree(putResp.getBody());
        assertThat(goalNode.get("purpose").asText()).isEqualTo("Discovery com lead Acme");
        assertThat(goalNode.get("expectedOutcomes").size()).isEqualTo(2);

        // 2) Run the synchronous analysis (auto-dispatch off in the test profile).
        analysisService.run(meetingId, principalTenantId(token));

        // 3) GET includes goal + productivity.
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.get("goal")).isNotNull();
        assertThat(detail.get("goal").isNull()).isFalse();
        assertThat(detail.get("goal").get("purpose").asText()).isEqualTo("Discovery com lead Acme");
        assertThat(detail.get("productivity").isNull()).isFalse();
        assertThat(detail.get("productivity").get("score").asInt()).isEqualTo(75);
        assertThat(detail.get("productivity").get("band").asText()).isEqualTo("HIGH");
        assertThat(detail.get("productivity").get("coverage").size()).isEqualTo(2);
        assertThat(detail.get("productivity").get("rationale").asText()).contains("stub");
    }

    @Test
    void deleteGoalAlsoClearsProductivity() throws Exception {
        String token = signupAndLogin("del@nora.dev", "SenhaForte123", "Owner Del");
        UUID meetingId = uploadMeeting(token, "Reuniao com delete");

        // Set goal, run the analysis (generates productivity).
        putGoal(
                meetingId,
                token,
                Map.of(
                        "purpose",
                        "Refinement",
                        "expectedOutcomes",
                        List.of("decidir storyA", "decidir storyB")));
        analysisService.run(meetingId, principalTenantId(token));

        JsonNode before = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(before.get("productivity").isNull()).isFalse();

        // DELETE goal.
        ResponseEntity<String> delResp = deleteGoal(meetingId, token);
        assertThat(delResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode after = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(after.get("goal").isNull()).isTrue();
        assertThat(after.get("productivity").isNull()).isTrue();
    }

    @Test
    void putGoalAcrossTenantsIsIsolated() throws Exception {
        String tokenA = signupAndLogin("a@nora.dev", "SenhaForte123", "A");
        String tokenB = signupAndLogin("b@nora.dev", "SenhaForte123", "B");
        UUID meetingA = uploadMeeting(tokenA, "Reuniao tenant A");

        // Tenant B tries to update the goal of a tenant A meeting => 404.
        Map<String, Object> body =
                Map.of("purpose", "tentar invadir", "expectedOutcomes", List.of("vazar dados"));
        ResponseEntity<String> resp = putGoal(meetingA, tokenB, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Confirms nothing was persisted to A's meeting from B's side.
        JsonNode detail = authGet("/meetings/" + meetingA, tokenA).read(HttpStatus.OK);
        assertThat(detail.get("goal").isNull()).isTrue();
    }

    @Test
    void putGoalWithMissingPurposeReturns400() throws Exception {
        String token = signupAndLogin("bad@nora.dev", "SenhaForte123", "Bad");
        UUID meetingId = uploadMeeting(token, "Reuniao bad");

        Map<String, Object> body = Map.of("expectedOutcomes", List.of("outcome 1"));
        ResponseEntity<String> resp = putGoal(meetingId, token, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void putGoalOnCompletedMeetingMarksPendingForReprocess() throws Exception {
        String token = signupAndLogin("repro@nora.dev", "SenhaForte123", "Repro");
        UUID meetingId = uploadMeeting(token, "Reuniao repro");

        // Run the initial analysis without a goal => productivity null.
        analysisService.run(meetingId, principalTenantId(token));
        JsonNode beforeGoal = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(beforeGoal.get("processingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(beforeGoal.get("productivity").isNull()).isTrue();

        // Now set the goal => the meeting must go back to PENDING.
        Map<String, Object> goalBody =
                Map.of(
                        "purpose",
                        "Refinement tardio",
                        "expectedOutcomes",
                        List.of("definir storyZ"));
        putGoal(meetingId, token, goalBody);

        JsonNode afterGoal = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(afterGoal.get("processingStatus").asText()).isEqualTo("PENDING");
    }

    @Test
    void puttingTheGoalTwiceReplacesItInsteadOfFailing() throws Exception {
        // The upsert deletes the previous row with a NATIVE query, which the persistence context
        // cannot see: the entity read moments earlier in the same transaction stays MANAGED under
        // an id whose row is gone, and the save that follows carries that same assigned id -- no
        // @Version, no Persistable, so it takes the merge branch and collides with the managed
        // copy on that EntityKey. The endpoint documents itself as an idempotent upsert and every
        // existing test only ever PUT once, so the second PUT was never exercised.
        String token = signupAndLogin("upsert@nora.dev", "SenhaForte123", "Upsert");
        UUID meetingId = uploadMeeting(token, "Reuniao upsert");

        ResponseEntity<String> first =
                putGoal(
                        meetingId,
                        token,
                        Map.of(
                                "purpose",
                                "Primeira versao",
                                "expectedOutcomes",
                                List.of("outcome A", "outcome B")));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID goalId = UUID.fromString(mapper.readTree(first.getBody()).get("id").asText());

        ResponseEntity<String> second =
                putGoal(
                        meetingId,
                        token,
                        Map.of(
                                "purpose",
                                "Segunda versao",
                                "expectedOutcomes",
                                List.of("outcome C")));
        assertThat(second.getStatusCode())
                .as("the second PUT of an idempotent upsert must succeed: %s", second.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode saved = mapper.readTree(second.getBody());
        assertThat(saved.get("purpose").asText()).isEqualTo("Segunda versao");
        assertThat(saved.get("expectedOutcomes").size()).isEqualTo(1);
        assertThat(UUID.fromString(saved.get("id").asText()))
                .as("the upsert keeps the goal id")
                .isEqualTo(goalId);

        // And the replacement is what a fresh read returns, not just what the write echoed back.
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.get("goal").get("purpose").asText()).isEqualTo("Segunda versao");
        assertThat(detail.get("goal").get("expectedOutcomes").size()).isEqualTo(1);
    }

    @Test
    void aSecondGoalEditThatAlsoRequeuesTheAnalysisSucceeds() throws Exception {
        // The goal upsert and the re-analysis claim in the SAME transaction: the goal write is
        // still pending in the persistence context when claimForReanalysis fires with
        // clearAutomatically, which is where a stale managed entity surfaces as a
        // StaleStateException on flush rather than at merge time.
        //
        // Reaching that needs the meeting COMPLETED at BOTH puts. The first version of this ran
        // the analysis once and then put twice -- but the first put already moves the row to
        // PENDING, so the second took the shouldReprocess=false branch, never touched
        // claimForReanalysis, and was an exact duplicate of the test above. The analysis is run
        // again in between, and the status after each put is what proves the branch was taken:
        // had the claim been skipped, the row would have stayed COMPLETED.
        String token = signupAndLogin("upsert2@nora.dev", "SenhaForte123", "Upsert2");
        UUID meetingId = uploadMeeting(token, "Reuniao upsert 2");
        UUID tenantId = principalTenantId(token);

        analysisService.run(meetingId, tenantId);
        assertThat(statusOf(meetingId, token)).isEqualTo("COMPLETED");
        assertThat(
                        putGoal(
                                        meetingId,
                                        token,
                                        Map.of("purpose", "v1", "expectedOutcomes", List.of("x")))
                                .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(statusOf(meetingId, token))
                .as("the first edit of an analysed meeting must queue a re-analysis")
                .isEqualTo("PENDING");

        // Back to COMPLETED, so the second edit takes the same branch as the first.
        analysisService.run(meetingId, tenantId);
        assertThat(statusOf(meetingId, token)).isEqualTo("COMPLETED");

        ResponseEntity<String> second =
                putGoal(
                        meetingId,
                        token,
                        Map.of("purpose", "v2", "expectedOutcomes", List.of("y")));
        assertThat(second.getStatusCode())
                .as("second edit through the reprocess branch: %s", second.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.get("processingStatus").asText())
                .as("the claim must have run on the second edit too, not been skipped")
                .isEqualTo("PENDING");
        assertThat(detail.get("goal").get("purpose").asText()).isEqualTo("v2");
        assertThat(detail.get("goal").get("expectedOutcomes").size()).isEqualTo(1);
    }

    private String statusOf(UUID meetingId, String token) throws Exception {
        return authGet("/meetings/" + meetingId, token)
                .read(HttpStatus.OK)
                .get("processingStatus")
                .asText();
    }

    /* ---------- helpers ---------- */

    private UUID principalTenantId(String token) throws Exception {
        // Resolve tenantId via the /meetings detail of any of the user's meetings; simplest: the
        // token will be used in a GET to force resolution. But cheaper: creating a helper meeting
        // is enough. Here we create a throwaway meeting just to read tenantId.
        UUID meeting = uploadMeeting(token, "tmp tenant probe " + UUID.randomUUID());
        JsonNode detail = authGet("/meetings/" + meeting, token).read(HttpStatus.OK);
        return UUID.fromString(detail.get("tenantId").asText());
    }

    private UUID uploadMeeting(String token, String title) throws Exception {
        String metadata =
                mapper.writeValueAsString(
                        Map.of("title", title, "language", "pt-BR", "transcriptFormat", "TXT"));
        String content =
                "Lucas: bom dia. Marina: bom dia.\n"
                        + "Lucas: vamos discutir o tema da reuniao. Marina: certo.";
        ResponseEntity<String> resp =
                multipartUpload(
                        "/meetings", token, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = mapper.readTree(resp.getBody());
        return UUID.fromString(body.get("id").asText());
    }

    private ResponseEntity<String> putGoal(UUID meetingId, String token, Map<String, ?> body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return rest.exchange(
                "/meetings/" + meetingId + "/goal", HttpMethod.PUT, entity, String.class);
    }

    private ResponseEntity<String> deleteGoal(UUID meetingId, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(
                "/meetings/" + meetingId + "/goal",
                HttpMethod.DELETE,
                new HttpEntity<>(headers),
                String.class);
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

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        return rest.postForEntity(path, entity, String.class);
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
     * Deterministic worker stub. Emits productivity only when there is a {@link MeetingGoal}
     * (mirrors the worker contract in ADR 0005).
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
                        Optional<MeetingGoal> goal) {
                    MeetingAnalysis analysis =
                            MeetingAnalysis.newAnalysis(
                                    meetingId,
                                    tenantId,
                                    "Resumo stub para a reuniao em teste integrado.",
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
                    if (goal.isEmpty()) {
                        return AnalysisResult.of(analysis);
                    }
                    MeetingGoal g = goal.get();
                    int n = g.expectedOutcomes().size();
                    List<OutcomeCoverage> coverage = new java.util.ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        coverage.add(
                                new OutcomeCoverage(
                                        g.expectedOutcomes().get(i).text(),
                                        CoverageStatus.ADDRESSED,
                                        "stub evidence",
                                        i));
                    }
                    ProductivityAssessment productivity =
                            ProductivityAssessment.newAssessment(
                                    tenantId,
                                    meetingId,
                                    75,
                                    ProductivityBand.HIGH,
                                    coverage,
                                    0.1,
                                    0.4,
                                    "rationale gerada pelo stub para teste integrado");
                    return AnalysisResult.of(analysis, productivity);
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

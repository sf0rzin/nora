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
 * Fluxo end-to-end do Productivity Score (ADR 0005): upload -> set goal -> rodar analise stub ->
 * GET retorna goal+productivity. Tambem cobre isolamento por tenant e DELETE de goal.
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

        // 1) Set goal (meeting ainda em PENDING).
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

        // 2) Rodar analise sincrona (auto-dispatch desligado em test profile).
        analysisService.run(meetingId, principalTenantId(token));

        // 3) GET inclui goal + productivity.
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

        // Set goal, rodar analise (gera productivity).
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

        // Tenant B tenta atualizar goal de meeting do tenant A => 404.
        Map<String, Object> body =
                Map.of("purpose", "tentar invadir", "expectedOutcomes", List.of("vazar dados"));
        ResponseEntity<String> resp = putGoal(meetingA, tokenB, body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Confirma que nada foi persistido pra meeting de A pelo lado de B.
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

        // Roda analise inicial sem goal => productivity null.
        analysisService.run(meetingId, principalTenantId(token));
        JsonNode beforeGoal = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(beforeGoal.get("processingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(beforeGoal.get("productivity").isNull()).isTrue();

        // Agora seta goal => meeting deve voltar pra PENDING.
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

    /* ---------- helpers ---------- */

    private UUID principalTenantId(String token) throws Exception {
        // Resolve tenantId via /meetings detail de qualquer meeting do user; mais simples: o token
        // sera usado num GET para forcar resolucao. Mas mais barato: criar um meeting helper basta.
        // Aqui, criamos uma meeting throwaway pra ler tenantId.
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
     * Stub determinista do worker. Emite productivity apenas quando ha {@link MeetingGoal} (espelha
     * o contrato do worker em ADR 0005).
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
            };
        }
    }
}

package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cross-tenant isolation of the task aggregate (the action items US22-US24 extracts from an
 * analysis), proven over HTTP against a real Postgres.
 *
 * <p>Why this file exists: {@code TaskServiceTest} already asserts the scoping, but only against an
 * in-memory fake whose {@code belongsToTenant} is the test's own logic. That fake cannot fail the
 * way production fails. The failure mode that matters is a dropped {@code AND tenant_id = ?} in the
 * repository's SQL, and a hand-written in-memory double will keep filtering correctly no matter
 * what the SQL says. Only a real query can catch it.
 *
 * <p>It matters because that filter is alone: the row-level security policies exist but are not
 * enforced at runtime, since the application connects as the table owner and the enforce flag
 * defaults to off. There is no second layer behind the application predicate.
 *
 * <p>The point about permissions. Since #407 authorization is deny-by-default, so a member with no
 * policies is refused before tenant scoping is ever consulted, and a test built on such a principal
 * would pass even with the tenant filter deleted. Both intruders here are therefore fully entitled
 * INSIDE their own tenant: the Root of tenant B (whose bypass in {@code AuthorizationService}
 * short-circuits the evaluator entirely) and a non-Root member of B holding {@code task:*} over
 * {@code nora:tenant/{B}:task/*}. The ARN the interceptor checks is built from {@code
 * principal.tenantId()}, so it always lands in the caller's own scope and always matches — leaving
 * the {@code tenant_id} predicate as the only thing that can refuse.
 *
 * <p>The refusal is 404, not 403: a 403 would confirm that the task id exists in some other tenant.
 * Same convention {@code MeetingFlowIntegrationTest} and {@code PrivacyFlowIntegrationTest} prove
 * for their aggregates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TaskIsolationIntegrationTest {

    private static final String PASSWORD = "SenhaForte123";
    private static final String TRANSCRIPT = "Ana: vamos fechar.\nBruno: mando na sexta.";

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

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AnalysisService analysisService;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /* ============================ GET /tasks ========================== */

    @Test
    void taskListing_neverReturnsAnotherTenantsActionItems() throws Exception {
        Workspace alfa = workspaceWithTasks("tasks-iso-a@nora.dev", "Alfa");
        Workspace beta = workspaceWithTasks("tasks-iso-b@nora.dev", "Beta");

        List<String> ofAlfa = taskIds(alfa.token());
        List<String> ofBeta = taskIds(beta.token());

        // Positive: each workspace really does have its own two action items to hide.
        assertThat(ofAlfa).hasSize(2);
        assertThat(ofBeta).hasSize(2);
        assertThat(ofBeta).doesNotContainAnyElementsOf(ofAlfa);

        // The same listing through a non-Root member of B holding task:* over B's own tasks.
        // The authorization gate is satisfied, so only the tenant_id predicate is filtering.
        String member = memberWithFullTaskRights(beta, "tasks-iso-b-member@nora.dev");
        assertThat(taskIds(member)).containsExactlyInAnyOrderElementsOf(ofBeta);
    }

    /* ========================= PATCH /tasks/{id} ====================== */

    @Test
    void taskUpdate_ofAnotherTenantsActionItem_answersNotFound() throws Exception {
        Workspace alfa = workspaceWithTasks("tasks-upd-a@nora.dev", "Alfa");
        Workspace beta = workspaceWithTasks("tasks-upd-b@nora.dev", "Beta");
        String taskOfAlfa = taskIds(alfa.token()).get(0);
        String taskOfBeta = taskIds(beta.token()).get(0);
        String pathAlfa = "/tasks/" + taskOfAlfa;
        String pathBeta = "/tasks/" + taskOfBeta;
        String done = json(Map.of("status", "DONE"));

        // Root of B: the IAM bypass makes the gate a no-op, so the refusal is the tenant filter.
        assertThat(patch(pathAlfa, done, beta.token())).isEqualTo(HttpStatus.NOT_FOUND);

        // Non-Root member of B carrying task:* over B's tasks: same answer, ordinary policy path.
        String member = memberWithFullTaskRights(beta, "tasks-upd-b-member@nora.dev");
        assertThat(patch(pathAlfa, done, member)).isEqualTo(HttpStatus.NOT_FOUND);

        // Refused means untouched: the action item is still OPEN in its own tenant.
        assertThat(taskStatus(alfa.token(), taskOfAlfa)).isEqualTo("OPEN");

        // Positive control on the very same endpoint and the very same payload.
        assertThat(patch(pathAlfa, done, alfa.token())).isEqualTo(HttpStatus.OK);
        assertThat(taskStatus(alfa.token(), taskOfAlfa)).isEqualTo("DONE");
        assertThat(patch(pathBeta, done, member)).isEqualTo(HttpStatus.OK);
    }

    /* ============================= fixtures =========================== */

    /** A tenant with its Root token and two extracted action items. */
    private record Workspace(String token, UUID tenantId) {}

    private Workspace workspaceWithTasks(String email, String name) throws Exception {
        String token = signupAndLogin(email, name);
        UUID tenantId = readClaim(token, "tenantId");
        String meeting = uploadMeeting(token, "Discovery " + name);
        analysisService.run(UUID.fromString(meeting), tenantId);
        return new Workspace(token, tenantId);
    }

    /**
     * A non-Root member of the given workspace holding {@code task:*} over every task of its OWN
     * tenant — everything it would need to succeed at home, and nothing that reaches abroad.
     */
    private String memberWithFullTaskRights(Workspace ws, String email) throws Exception {
        UUID memberId = insertActiveMember(ws.tenantId(), email, "Task Member");
        String scope = arn(ws.tenantId(), ":task/*");
        String doc = document(statement("Allow", "task:*", scope));
        attach(ws.token(), memberId, createPolicy(ws.token(), "AllowTasks", doc));
        return login(email);
    }

    /* ============================= helpers ============================ */

    private List<String> taskIds(String token) throws Exception {
        JsonNode listing = read(authGet("/tasks", token), HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        listing.get("items").forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private String taskStatus(String token, String taskId) throws Exception {
        JsonNode listing = read(authGet("/tasks", token), HttpStatus.OK);
        for (JsonNode item : listing.get("items")) {
            if (item.get("id").asText().equals(taskId)) {
                return item.get("status").asText();
            }
        }
        return null;
    }

    private static String arn(UUID tenantId, String suffix) {
        return "nora:tenant/" + tenantId + suffix;
    }

    private static String document(String... statements) {
        String body = String.join(",", statements);
        return "{\"version\":\"2026-05-07\",\"statements\":[" + body + "]}";
    }

    private static String statement(String effect, String action, String resource) {
        String head = "{\"effect\":\"" + effect + "\",\"action\":[\"" + action + "\"],";
        return head + "\"resource\":[\"" + resource + "\"]}";
    }

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        String body = json(Map.of("name", name, "document", mapper.readTree(documentJson)));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/iam/policies", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private void attach(String token, UUID userId, String policyId) throws Exception {
        String path = "/iam/users/" + userId + "/policies/" + policyId;
        read(exchange(HttpMethod.POST, path, null, token), HttpStatus.NO_CONTENT);
    }

    private HttpStatusCode patch(String path, String body, String token) {
        return exchange(HttpMethod.PATCH, path, body, token).getStatusCode();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> exchange(
            HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, String.class);
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private String signupAndLogin(String email, String name) throws Exception {
        String payload = json(Map.of("email", email, "password", PASSWORD, "displayName", name));
        JsonNode signup = postJson("/auth/signup", payload, HttpStatus.CREATED);
        String verify = json(Map.of("token", signup.get("emailVerificationDevToken").asText()));
        postJson("/auth/verify-email", verify, HttpStatus.NO_CONTENT);
        return login(email);
    }

    private String login(String email) throws Exception {
        String payload = json(Map.of("email", email, "password", PASSWORD));
        return postJson("/auth/login", payload, HttpStatus.OK).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String body, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp =
                rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
        return read(resp, expected);
    }

    /** Inserts an ACTIVE, email-verified, non-Root user into the given tenant. */
    private UUID insertActiveMember(UUID tenantId, String email, String displayName) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, display_name, status,"
                        + " is_root, email_verified_at, created_at, updated_at) VALUES (?, ?, ?, ?,"
                        + " ?, 'ACTIVE', FALSE, NOW(), NOW(), NOW())",
                userId,
                tenantId,
                email,
                passwordEncoder.encode(PASSWORD),
                displayName);
        return userId;
    }

    private String uploadMeeting(String token, String title) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaHeaders = new HttpHeaders();
        metaHeaders.setContentType(MediaType.APPLICATION_JSON);
        String metadata = json(Map.of("title", title, "transcriptFormat", "TXT"));
        body.add("metadata", new HttpEntity<>(metadata, metaHeaders));

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource file =
                new ByteArrayResource(TRANSCRIPT.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "transcript.txt";
                    }
                };
        body.add("file", new HttpEntity<>(file, fileHeaders));

        ResponseEntity<String> resp =
                rest.postForEntity("/meetings", new HttpEntity<>(body, headers), String.class);
        return read(resp, HttpStatus.ACCEPTED).get("id").asText();
    }

    private UUID readClaim(String jwt, String claim) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        String payload = new String(decoded, StandardCharsets.UTF_8);
        return UUID.fromString(mapper.readTree(payload).get(claim).asText());
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }

    /* ======================= test-scoped beans ====================== */

    @TestConfiguration
    static class TestBeans {

        /** Deterministic worker stub: each analysis has to yield exactly two action items. */
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
                                    "Resumo stub para o teste de isolamento de tarefas.",
                                    Sentiment.NEUTRAL,
                                    List.of("isolamento"),
                                    List.of(),
                                    List.of(
                                            ActionItem.fresh(
                                                    "Primeira tarefa",
                                                    null,
                                                    null,
                                                    Priority.HIGH,
                                                    "mando na sexta"),
                                            ActionItem.fresh(
                                                    "Segunda tarefa",
                                                    null,
                                                    null,
                                                    Priority.MEDIUM,
                                                    "vamos fechar")),
                                    List.of(),
                                    List.of(),
                                    "stub-task-iso-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    0);
                    return AnalysisResult.of(analysis);
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

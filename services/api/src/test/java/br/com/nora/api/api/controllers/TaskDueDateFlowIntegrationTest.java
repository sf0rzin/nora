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
import java.util.Base64;
import java.util.HashMap;
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
 * Writing a task's due date over HTTP, against a real Postgres.
 *
 * <p>The field was read-only end to end: persisted by the extraction, returned by the listing,
 * rendered by the web app and consumed by the Flows follow-up scheduler, with no endpoint able to
 * write it. A wrong date guessed by the model therefore drove automation that nobody could correct.
 *
 * <p>A real database is the point of this file rather than a second unit test. The update binds the
 * value as text and casts it in SQL, because a native query cannot infer the type of a plain null
 * bind and clearing the date is exactly the case that binds null — an in-memory double would accept
 * either shape and prove nothing about the one that runs in production.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TaskDueDateFlowIntegrationTest {

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
    @Autowired AnalysisService analysisService;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void aDueDateIsWrittenAndComesBackOnTheListing() throws Exception {
        String token = workspaceWithOneTask("due-write@nora.dev", "Alfa");
        String taskId = firstTaskId(token);

        JsonNode updated = patchOk(taskId, token, Map.of("dueDate", "2026-09-01"));

        assertThat(updated.get("dueDate").asText()).isEqualTo("2026-09-01");
        assertThat(dueDateOnListing(token, taskId)).isEqualTo("2026-09-01");
    }

    @Test
    void anEmptyDueDateClearsIt() throws Exception {
        // The documented way to remove a date the extraction invented. Absent would mean "leave it
        // alone", so without this the field would be correctable but never erasable.
        String token = workspaceWithOneTask("due-clear@nora.dev", "Beta");
        String taskId = firstTaskId(token);
        patchOk(taskId, token, Map.of("dueDate", "2026-09-01"));

        JsonNode cleared = patchOk(taskId, token, Map.of("dueDate", ""));

        assertThat(cleared.hasNonNull("dueDate")).isFalse();
        assertThat(dueDateOnListing(token, taskId)).isNull();
    }

    @Test
    void anUnparseableDueDateIsAValidationErrorAndNotAServerError() throws Exception {
        String token = workspaceWithOneTask("due-bad@nora.dev", "Gama");
        String taskId = firstTaskId(token);

        ResponseEntity<String> resp = patch(taskId, token, Map.of("dueDate", "01/09/2026"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(mapper.readTree(resp.getBody()).get("code").asText())
                .isEqualTo("TASK_INVALID_DUE_DATE");
        assertThat(dueDateOnListing(token, taskId)).isNull();
    }

    @Test
    void aBodyThatTouchesNoFieldIsStillRejected() throws Exception {
        // The endpoint refused an empty body before the due date existed, and has to keep doing it
        // now that "present but empty" means something for one of the three fields.
        String token = workspaceWithOneTask("due-empty@nora.dev", "Delta");
        String taskId = firstTaskId(token);

        ResponseEntity<String> resp = patch(taskId, token, Map.of());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void statusTitleAndDueDateSentTogetherAllLand() throws Exception {
        // Each field is its own statement, and the response is built from the last one. If that
        // last read did not carry the earlier writes, the client would render a stale row.
        String token = workspaceWithOneTask("due-combo@nora.dev", "Epsilon");
        String taskId = firstTaskId(token);

        Map<String, String> body = new HashMap<>();
        body.put("status", "IN_PROGRESS");
        body.put("title", "Enviar proposta revisada");
        body.put("dueDate", "2026-09-15");
        JsonNode updated = patchOk(taskId, token, body);

        assertThat(updated.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(updated.get("title").asText()).isEqualTo("Enviar proposta revisada");
        assertThat(updated.get("dueDate").asText()).isEqualTo("2026-09-15");
    }

    /* ============================= fixtures =========================== */

    private String workspaceWithOneTask(String email, String name) throws Exception {
        String token = signupAndLogin(email, name);
        UUID tenantId = readClaim(token, "tenantId");
        String meeting = uploadMeeting(token, "Discovery " + name);
        analysisService.run(UUID.fromString(meeting), tenantId);
        return token;
    }

    private String firstTaskId(String token) throws Exception {
        JsonNode listing = read(authGet("/tasks", token), HttpStatus.OK);
        assertThat(listing.get("items").size()).isPositive();
        return listing.get("items").get(0).get("id").asText();
    }

    private String dueDateOnListing(String token, String taskId) throws Exception {
        JsonNode listing = read(authGet("/tasks", token), HttpStatus.OK);
        for (JsonNode item : listing.get("items")) {
            if (item.get("id").asText().equals(taskId)) {
                return item.hasNonNull("dueDate") ? item.get("dueDate").asText() : null;
            }
        }
        return null;
    }

    private JsonNode patchOk(String taskId, String token, Map<String, String> body)
            throws Exception {
        return read(patch(taskId, token, body), HttpStatus.OK);
    }

    private ResponseEntity<String> patch(String taskId, String token, Map<String, String> body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return rest.exchange("/tasks/" + taskId, HttpMethod.PATCH, entity, String.class);
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String signupAndLogin(String email, String name) throws Exception {
        String payload =
                mapper.writeValueAsString(
                        Map.of("email", email, "password", PASSWORD, "displayName", name));
        JsonNode signup = postJson("/auth/signup", payload, HttpStatus.CREATED);
        String verify =
                mapper.writeValueAsString(
                        Map.of("token", signup.get("emailVerificationDevToken").asText()));
        postJson("/auth/verify-email", verify, HttpStatus.NO_CONTENT);
        String login = mapper.writeValueAsString(Map.of("email", email, "password", PASSWORD));
        return postJson("/auth/login", login, HttpStatus.OK).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String body, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp =
                rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
        return read(resp, expected);
    }

    private String uploadMeeting(String token, String title) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaHeaders = new HttpHeaders();
        metaHeaders.setContentType(MediaType.APPLICATION_JSON);
        String metadata =
                mapper.writeValueAsString(Map.of("title", title, "transcriptFormat", "TXT"));
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

        /** Worker stub: one action item with NO due date, so the writes here start from null. */
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
                                    "Resumo stub para o teste de data de vencimento.",
                                    Sentiment.NEUTRAL,
                                    List.of("prazo"),
                                    List.of(),
                                    List.of(
                                            ActionItem.fresh(
                                                    "Enviar proposta",
                                                    null,
                                                    null,
                                                    Priority.HIGH,
                                                    "mando na sexta")),
                                    List.of(),
                                    List.of(),
                                    "stub-task-due-1",
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

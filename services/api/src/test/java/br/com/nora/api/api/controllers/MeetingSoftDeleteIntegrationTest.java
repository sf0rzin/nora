package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.TranscriptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@code DELETE /meetings/{id}} — the REVERSIBLE removal (ADR 0021, V013).
 *
 * <p>Sibling of {@code PrivacyFlowIntegrationTest}, which covers the irreversible one, and the
 * contrast between the two is what this class is really about. Until this endpoint existed the
 * whole soft-delete machinery — the {@code deleted_at} column, the partial unique indexes, the
 * entity's {@code @SQLDelete} and {@code @SQLRestriction}, and the {@code deleted_at IS NULL}
 * predicates repeated across the native queries — defended against a state no code path could
 * produce. So these tests assert the two halves that matter: the meeting really does disappear from
 * every read, and the PII really does survive, because "remove this from my list" must not mean
 * "destroy the transcript of everyone who was in the room".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MeetingSoftDeleteIntegrationTest {

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
    @Autowired TranscriptRepository transcripts;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void delete_removesTheMeetingFromEveryReadAndKeepsTheData() throws Exception {
        String token = signupAndLogin("soft-owner@nora.dev", "Soft Owner");
        JsonNode uploaded = upload(token, "Kickoff", "Marina: alinhamento do escopo.");
        String meetingId = uploaded.get("id").asText();
        UUID tenantId = UUID.fromString(uploaded.get("tenantId").asText());

        assertThat(authDelete("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Gone from the detail and from the listing: deleted_at is what @SQLRestriction reads.
        assertThat(authGet("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(authGet("/meetings", token), HttpStatus.OK).get("totalItems").asInt())
                .isZero();

        // And this is the whole difference from the LGPD erasure: the row and its transcript are
        // still there. A user who removed the wrong upload has not destroyed anyone's PII.
        assertThat(transcripts.findByMeetingAndTenant(UUID.fromString(meetingId), tenantId))
                .as("a reversible removal must not purge the transcript")
                .isPresent();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted_at IS NOT NULL FROM meetings WHERE id = ?",
                                Boolean.class,
                                UUID.fromString(meetingId)))
                .isTrue();
    }

    /**
     * The action items of a removed meeting have to go with it. This is the assertion the native
     * task queries had no predicate for: they joined {@code meetings} without {@code deleted_at IS
     * NULL}, and native SQL does not see the entity's restriction — so the day a writer for that
     * column appeared, {@code GET /tasks} would have kept listing the tasks of removed meetings,
     * under the removed meeting's title, and {@code PATCH /tasks/{id}} would have kept editing
     * them.
     */
    @Test
    void delete_takesTheMeetingsTasksOutOfTheTaskList() throws Exception {
        String token = signupAndLogin("soft-tasks@nora.dev", "Soft Tasks");
        JsonNode uploaded = upload(token, "Planning", "Marina: fechamos o escopo.");
        UUID meetingId = UUID.fromString(uploaded.get("id").asText());
        UUID tenantId = UUID.fromString(uploaded.get("tenantId").asText());
        seedActionItem(meetingId, tenantId, "Enviar proposta revisada");

        JsonNode before = read(authGet("/tasks", token), HttpStatus.OK);
        assertThat(before.get("items").size()).isEqualTo(1);

        assertThat(authDelete("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode after = read(authGet("/tasks", token), HttpStatus.OK);
        assertThat(after.get("items").size()).isZero();
        assertThat(after.get("totalItems").asInt()).isZero();
    }

    /**
     * Removing twice is not an error the second time only because the caller was slow: the second
     * call did not remove anything, and saying 204 would claim it did. 404 is the same answer the
     * endpoint gives for a meeting that never existed, which is correct — a removed meeting does
     * not exist as far as the product is concerned.
     */
    @Test
    void delete_twice_answers404TheSecondTime() throws Exception {
        String token = signupAndLogin("soft-twice@nora.dev", "Soft Twice");
        String meetingId = upload(token, "Retro", "Marina: ok.").get("id").asText();

        assertThat(authDelete("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(authDelete("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void delete_otherTenantsMeeting_isRefusedAndChangesNothing() throws Exception {
        String tokenA = signupAndLogin("soft-a@nora.dev", "Soft A");
        String meetingId = upload(tokenA, "Do A", "conteudo do tenant A").get("id").asText();

        String tokenB = signupAndLogin("soft-b@nora.dev", "Soft B");
        // 404 rather than 403: the answer must not reveal that the meeting exists elsewhere.
        assertThat(authDelete("/meetings/" + meetingId, tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(authGet("/meetings/" + meetingId, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void delete_requiresAuthentication() {
        ResponseEntity<String> resp =
                rest.exchange(
                        "/meetings/" + UUID.randomUUID(),
                        HttpMethod.DELETE,
                        new HttpEntity<>(new HttpHeaders()),
                        String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    /**
     * Writes one analysis with one action item straight to the database. The analysis pipeline is
     * off in the test profile and stubbing the worker would put a second moving part in front of
     * the thing under test, which is a SQL predicate.
     */
    private void seedActionItem(UUID meetingId, UUID tenantId, String title) {
        UUID analysisId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO meeting_analyses (id, meeting_id, tenant_id, summary,"
                        + " sentiment_overall) VALUES (?, ?, ?, ?, 'NEUTRAL')",
                analysisId,
                meetingId,
                tenantId,
                "resumo");
        jdbc.update(
                "INSERT INTO meeting_action_items (id, analysis_id, tenant_id, title, priority,"
                        + " source_quote, status, position) VALUES (?, ?, ?, ?, 'MEDIUM', 'quote',"
                        + " 'OPEN', 0)",
                UUID.randomUUID(),
                analysisId,
                tenantId,
                title);
    }

    private JsonNode upload(String token, String title, String content) throws Exception {
        String metadata =
                mapper.writeValueAsString(Map.of("title", title, "transcriptFormat", "TXT"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaH = new HttpHeaders();
        metaH.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(metadata, metaH));
        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource file =
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "t.txt";
                    }
                };
        body.add("file", new HttpEntity<>(file, fileH));

        return read(
                rest.postForEntity("/meetings", new HttpEntity<>(body, headers), String.class),
                HttpStatus.ACCEPTED);
    }

    private String signupAndLogin(String email, String name) throws Exception {
        String password = "SenhaForte123";
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        JsonNode signup =
                read(
                        rest.postForEntity(
                                "/auth/signup",
                                new HttpEntity<>(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email", email,
                                                        "password", password,
                                                        "displayName", name)),
                                        json),
                                String.class),
                        HttpStatus.CREATED);
        read(
                rest.postForEntity(
                        "/auth/verify-email",
                        new HttpEntity<>(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "token",
                                                signup.get("emailVerificationDevToken").asText())),
                                json),
                        String.class),
                HttpStatus.NO_CONTENT);
        JsonNode login =
                read(
                        rest.postForEntity(
                                "/auth/login",
                                new HttpEntity<>(
                                        mapper.writeValueAsString(
                                                Map.of("email", email, "password", password)),
                                        json),
                                String.class),
                        HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private ResponseEntity<String> authDelete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

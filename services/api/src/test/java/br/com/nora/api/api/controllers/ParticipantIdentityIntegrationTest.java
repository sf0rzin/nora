package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
 * Participant identity over HTTP against a real Postgres (US13, ADR 0048).
 *
 * <p>Three properties are proven here rather than against a fake, because a fake cannot fail the
 * way production fails:
 *
 * <ul>
 *   <li><b>Matching never crosses tenants.</b> Two workspaces declare byte-identical rosters. A
 *       hand-written in-memory double would keep filtering correctly no matter what the SQL says;
 *       only a real query catches a dropped {@code AND tenant_id = ?}. It matters because that
 *       predicate is alone — the RLS policies exist but are not enforced at runtime here, since the
 *       application connects as the table owner and the enforce flag defaults to off.
 *   <li><b>Identity respects the per-item IAM decision.</b> A member holding {@code meeting:read}
 *       over the tenant but carrying a Deny on one meeting must not see the people who appear only
 *       in it. "Ana was in 3 meetings" is a fact about meetings, and a count is information.
 *   <li><b>An identity cannot outlive the meetings that produced it.</b> ADR 0048 §4 keeps no
 *       identity table precisely so that the ADR 0029 erasure carries it; this is the assertion
 *       that would fail if a future change started persisting one without a cascade.
 * </ul>
 *
 * <p>The intruder is deliberately a fully entitled principal inside its OWN tenant — Root, whose
 * bypass short-circuits the evaluator entirely — so that the tenant predicate is the only thing
 * left that can refuse. Same reasoning {@code TaskIsolationIntegrationTest} spells out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ParticipantIdentityIntegrationTest {

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

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void theSamePersonNamedTwoWaysAcrossTwoMeetingsIsOnePerson() throws Exception {
        String token = signupAndLogin("participants-match@nora.dev", "Match Root");
        uploadMeeting(token, "Discovery", roster("Ana Paula Silva", "Bruno Dias"));
        uploadMeeting(token, "Follow-up", roster("ana silva", "Sr. Bruno Dias"));

        JsonNode items = participants(token);
        assertThat(displayNames(items)).containsExactlyInAnyOrder("Ana Paula Silva", "Bruno Dias");

        JsonNode ana = byDisplayName(items, "Ana Paula Silva");
        assertThat(ana.get("meetingCount").asInt()).isEqualTo(2);
        assertThat(ana.get("meetings").size()).isEqualTo(2);
        // Nothing is lost to the merge: both spellings come back with it.
        assertThat(variantsOf(ana)).containsExactlyInAnyOrder("Ana Paula Silva", "ana silva");
    }

    @Test
    void identitiesNeverCrossTheTenantBoundary() throws Exception {
        String alfa = signupAndLogin("participants-iso-a@nora.dev", "Alfa Root");
        String beta = signupAndLogin("participants-iso-b@nora.dev", "Beta Root");
        // Byte-identical rosters, so a leak cannot hide behind a difference in the data.
        uploadMeeting(alfa, "Discovery Alfa", roster("Ana Paula Silva", "Bruno Dias"));
        uploadMeeting(beta, "Discovery Beta", roster("Ana Paula Silva", "Bruno Dias"));

        JsonNode ofAlfa = participants(alfa);
        JsonNode ofBeta = participants(beta);

        // Positive control: each workspace really does have two people to keep to itself.
        assertThat(displayNames(ofAlfa)).containsExactlyInAnyOrder("Ana Paula Silva", "Bruno Dias");
        assertThat(displayNames(ofBeta)).containsExactlyInAnyOrder("Ana Paula Silva", "Bruno Dias");

        // The identity id is derived from the person, so identical rosters DO share it across
        // tenants — that is by design and is not a leak. What must never be shared is a meeting,
        // and neither must a count that a meeting contributed to.
        assertThat(meetingIds(ofBeta)).doesNotContainAnyElementsOf(meetingIds(ofAlfa));
        assertThat(countOf(ofAlfa, "Ana Paula Silva")).isEqualTo(1);
        assertThat(countOf(ofBeta, "Ana Paula Silva")).isEqualTo(1);
    }

    @Test
    void aDeniedMeetingDoesNotContributeItsPeople() throws Exception {
        String root = signupAndLogin("participants-authz@nora.dev", "Authz Root");
        UUID tenantId = readClaim(root, "tenantId");
        String visible = uploadMeeting(root, "Visible", roster("Ana Paula Silva"));
        String denied = uploadMeeting(root, "Denied", roster("Carla Nunes"));

        // meeting:read over the whole tenant, minus one meeting. The Deny is what forces the
        // per-item path: a statement that distinguishes two meetings makes the decision
        // non-uniform, so the aggregate cannot be answered tenant-wide.
        String memberEmail = "participants-authz-member@nora.dev";
        UUID memberId = insertActiveMember(tenantId, memberEmail, "Member");
        String doc =
                document(
                        statement("Allow", "meeting:read", arn(tenantId, ":meeting/*")),
                        statement("Deny", "meeting:read", arn(tenantId, ":meeting/" + denied)));
        attach(root, memberId, createPolicy(root, "ReadMostMeetings", doc));
        String member = login(memberEmail);

        assertThat(displayNames(participants(root)))
                .containsExactlyInAnyOrder("Ana Paula Silva", "Carla Nunes");

        JsonNode ofMember = participants(member);
        assertThat(displayNames(ofMember)).containsExactly("Ana Paula Silva");
        assertThat(meetingIds(ofMember)).containsExactly(visible);
    }

    @Test
    void erasingTheLastMeetingErasesThePerson() throws Exception {
        String token = signupAndLogin("participants-erase@nora.dev", "Erase Root");
        UUID tenantId = readClaim(token, "tenantId");
        String first = uploadMeeting(token, "Discovery", roster("Ana Paula Silva", "Bruno Dias"));
        String second = uploadMeeting(token, "Follow-up", roster("Ana Silva"));

        assertThat(displayNames(participants(token)))
                .containsExactlyInAnyOrder("Ana Paula Silva", "Bruno Dias");

        // Bruno appears only in the first meeting, so erasing it takes him with it. Ana survives
        // on the roster of the second, with her count down by one.
        erase(token, first);
        JsonNode afterFirst = participants(token);
        assertThat(displayNames(afterFirst)).containsExactly("Ana Silva");
        assertThat(countOf(afterFirst, "Ana Silva")).isEqualTo(1);

        // ADR 0048 §4: nothing persists an identity, so the last erasure leaves nothing behind —
        // no projection to return and no row it could have been rebuilt from.
        erase(token, second);
        assertThat(displayNames(participants(token))).isEmpty();
        assertThat(participantRowsOf(tenantId)).isZero();
    }

    /* ============================= helpers ============================ */

    private JsonNode participants(String token) throws Exception {
        JsonNode body = read(authGet("/meetings/participants", token), HttpStatus.OK);
        return body.get("items");
    }

    private void erase(String token, String meetingId) throws Exception {
        ResponseEntity<String> resp =
                exchange(HttpMethod.DELETE, "/privacy/meetings/" + meetingId, null, token);
        read(resp, HttpStatus.NO_CONTENT);
    }

    /** Scoped to the tenant: every test in this class shares one container and one database. */
    private int participantRowsOf(UUID tenantId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM meeting_participants WHERE tenant_id = ?",
                        Integer.class,
                        tenantId);
        return count == null ? 0 : count;
    }

    private static List<Map<String, Object>> roster(String... displayNames) {
        List<Map<String, Object>> out = new ArrayList<>(displayNames.length);
        for (String name : displayNames) {
            out.add(Map.<String, Object>of("displayName", name, "isInternal", Boolean.TRUE));
        }
        return out;
    }

    private static int countOf(JsonNode items, String displayName) {
        return byDisplayName(items, displayName).get("meetingCount").asInt();
    }

    private static JsonNode byDisplayName(JsonNode items, String displayName) {
        for (JsonNode item : items) {
            if (item.get("displayName").asText().equals(displayName)) {
                return item;
            }
        }
        throw new AssertionError("no identity named " + displayName + " in " + items);
    }

    private static List<String> displayNames(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(item -> out.add(item.get("displayName").asText()));
        return out;
    }

    private static List<String> variantsOf(JsonNode item) {
        List<String> out = new ArrayList<>();
        item.get("variants").forEach(v -> out.add(v.asText()));
        return out;
    }

    private static List<String> meetingIds(JsonNode items) {
        List<String> out = new ArrayList<>();
        items.forEach(item -> item.get("meetings").forEach(m -> out.add(m.get("id").asText())));
        return out;
    }

    private static String arn(UUID tenantId, String suffix) {
        return "nora:tenant/" + tenantId + suffix;
    }

    private static String document(String... statements) {
        return "{\"version\":\"2026-05-07\",\"statements\":[" + String.join(",", statements) + "]}";
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

    private String uploadMeeting(String token, String title, List<Map<String, Object>> roster)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaHeaders = new HttpHeaders();
        metaHeaders.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> fields =
                Map.of("title", title, "transcriptFormat", "TXT", "participants", roster);
        String metadata = json(fields);
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
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
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

    private UUID readClaim(String jwt, String claim) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        String payload = new String(decoded, StandardCharsets.UTF_8);
        return UUID.fromString(mapper.readTree(payload).get(claim).asText());
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

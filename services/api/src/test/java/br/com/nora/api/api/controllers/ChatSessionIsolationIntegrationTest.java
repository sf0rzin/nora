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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Isolation of the chat session aggregate (V022) — the assistant's conversation history. The
 * aggregate had no test over its endpoints at all, and it is the aggregate with TWO boundaries
 * rather than one: {@code ChatSessionController} passes both {@code principal.tenantId()} and
 * {@code principal.userId()} on every call, so a session must be unreachable both from another
 * tenant and from another user inside the same tenant.
 *
 * <p>Why this file exists: the row-level security written in V022 is not enforced at runtime — the
 * policies are defined, but the application connects as the table owner and the enforce flag
 * defaults to off, which leaves the policies inert. The {@code tenant_id} + {@code user_id}
 * predicates in {@code ChatSessionService}/{@code ChatSessionRepository} are therefore the ONLY
 * control keeping one workspace out of another's history, and a filter with no test is a filter one
 * refactor away from disappearing.
 *
 * <p>Every handler here carries {@code @AuthorizationNotRequired} ("self" opt-out), so there is no
 * IAM gate in front of them: any authenticated principal reaches the handler body and only the
 * scoping predicates can refuse. That is what makes these assertions a test of the filter instead
 * of a test of the authorization gate — and it is why the intruder needs no policy granted to it.
 *
 * <p>Refusals are 404, never 403, matching what {@code MeetingFlowIntegrationTest} and {@code
 * PrivacyFlowIntegrationTest} prove for their aggregates: a 403 would confirm that the session id
 * exists somewhere else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ChatSessionIsolationIntegrationTest {

    private static final String PASSWORD = "SenhaForte123";

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

    /* ===================== boundary 1: the tenant ===================== */

    @Test
    void chatSessions_ofAnotherTenant_areInvisibleAndUnreachable() throws Exception {
        String tokenA = signupAndLogin("chat-iso-a@nora.dev", "Chat Alfa");
        String sessionA = createSession(tokenA, "Planejamento do Alfa");
        appendMessage(tokenA, sessionA, "Assunto confidencial do tenant Alfa");

        String tokenB = signupAndLogin("chat-iso-b@nora.dev", "Chat Beta");
        String sessionB = createSession(tokenB, "Planejamento do Beta");

        // The listing is scoped: B sees its own session and never A's.
        assertThat(sessionIds(tokenB)).containsExactly(sessionB);

        // Detail, message append, rename and delete all refuse A's session for B.
        assertEveryHandlerRefuses(sessionA, tokenB);

        // Refused means untouched: A's session and its single message survived every attempt.
        JsonNode detail = read(authGet("/chat/sessions/" + sessionA, tokenA), HttpStatus.OK);
        assertThat(detail.get("title").asText()).isEqualTo("Planejamento do Alfa");
        assertThat(detail.get("messages")).hasSize(1);
        assertThat(sessionIds(tokenA)).containsExactly(sessionA);

        // Positive control: the very same four handlers work for B on B's own session. Without
        // this, the test above would pass just as happily with the endpoints broken for everyone.
        assertEveryHandlerAccepts(sessionB, tokenB);
    }

    /* ================== boundary 2: the user in-tenant ================= */

    @Test
    void chatSessions_ofAnotherUserInTheSameTenant_areUnreachable() throws Exception {
        String owner = signupAndLogin("chat-iso-owner@nora.dev", "Chat Owner");
        UUID tenantId = readClaim(owner, "tenantId");
        String sessionOwner = createSession(owner, "Rascunho da dona da conta");
        appendMessage(owner, sessionOwner, "Anotacao pessoal da dona da conta");

        // Same tenant, different user. It holds no policy at all, which is fine precisely
        // because these handlers declare the "self" opt-out and never consult the evaluator.
        insertActiveMember(tenantId, "chat-iso-colleague@nora.dev", "Chat Colleague");
        String colleague = login("chat-iso-colleague@nora.dev");
        String sessionColleague = createSession(colleague, "Rascunho do colega");

        assertThat(sessionIds(colleague)).containsExactly(sessionColleague);
        assertEveryHandlerRefuses(sessionOwner, colleague);

        JsonNode detail = read(authGet("/chat/sessions/" + sessionOwner, owner), HttpStatus.OK);
        assertThat(detail.get("messages")).hasSize(1);

        assertEveryHandlerAccepts(sessionColleague, colleague);
    }

    /* ============================ assertions ========================== */

    /** The four id-addressed handlers, each of which must answer 404 for a foreign session. */
    private void assertEveryHandlerRefuses(String sessionId, String token) throws Exception {
        String path = "/chat/sessions/" + sessionId;
        String messages = path + "/messages";
        assertThat(getStatus(path, token)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(messages, messageBody(), token)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patch(path, renameBody(), token)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(path, token)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * The same four handlers on a session the caller does own — a deny-only test proves nothing.
     */
    private void assertEveryHandlerAccepts(String sessionId, String token) throws Exception {
        String path = "/chat/sessions/" + sessionId;
        String messages = path + "/messages";
        assertThat(getStatus(path, token)).isEqualTo(HttpStatus.OK);
        assertThat(post(messages, messageBody(), token)).isEqualTo(HttpStatus.CREATED);
        assertThat(patch(path, renameBody(), token)).isEqualTo(HttpStatus.OK);
        assertThat(delete(path, token)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /* ============================= helpers ============================ */

    private String createSession(String token, String title) throws Exception {
        String body = json(Map.of("title", title));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/chat/sessions", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private void appendMessage(String token, String sessionId, String text) throws Exception {
        String path = "/chat/sessions/" + sessionId + "/messages";
        String body = json(Map.of("role", "user", "content", text));
        read(exchange(HttpMethod.POST, path, body, token), HttpStatus.CREATED);
    }

    private List<String> sessionIds(String token) throws Exception {
        JsonNode listing = read(authGet("/chat/sessions", token), HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        listing.forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private String messageBody() throws Exception {
        return json(Map.of("role", "user", "content", "tentativa de outro principal"));
    }

    private String renameBody() throws Exception {
        return json(Map.of("title", "renomeado por outro principal"));
    }

    private HttpStatusCode getStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private HttpStatusCode post(String path, String body, String token) {
        return exchange(HttpMethod.POST, path, body, token).getStatusCode();
    }

    private HttpStatusCode patch(String path, String body, String token) {
        return exchange(HttpMethod.PATCH, path, body, token).getStatusCode();
    }

    private HttpStatusCode delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, null, token).getStatusCode();
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

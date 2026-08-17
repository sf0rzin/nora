package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * US41 — {@code GET /iam/policy-templates}, and the property the whole story rests on: a policy
 * made from a template is not a special kind of policy.
 *
 * <p>The endpoint is read-only and there is no instantiate endpoint, so instantiating is posting
 * the returned document to {@code POST /iam/policies}. That makes the test worth writing an
 * end-to-end one: every template goes through the ordinary create handler and comes back byte-equal
 * from the ordinary read handler, and the policy it produced decides real requests through the same
 * evaluator, checked here through {@code POST /iam/simulate}.
 *
 * <p>The round trip is also the regression guard for the response shape. The read side used to emit
 * the domain record's component names ({@code actions}, {@code resources}, {@code "ALLOW"}) while
 * the write side parses {@code action} and {@code resource}, so a document could be read but never
 * written back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamPolicyTemplatesIntegrationTest {

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

    /* =========================== the catalogue ======================== */

    @Test
    void theCatalogueIsRenderedForTheCallersOwnTenant() throws Exception {
        String root = signupAndLogin("tpl-scope@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");

        JsonNode templates = read(exchange(HttpMethod.GET, "/iam/policy-templates", null, root));

        assertThat(templates.isArray()).isTrue();
        assertThat(templates.size()).isPositive();
        for (JsonNode template : templates) {
            assertThat(template.path("id").asText()).isNotBlank();
            assertThat(template.path("description").asText()).isNotBlank();
            for (JsonNode statement : template.path("document").path("statements")) {
                assertThat(statement.path("effect").asText()).isIn("Allow", "Deny");
                assertThat(statement.path("action").isArray()).isTrue();
                for (JsonNode resource : statement.path("resource")) {
                    assertThat(resource.asText()).startsWith("nora:tenant/" + tenant);
                }
            }
        }
    }

    /* ======================== the round trip ========================== */

    /**
     * Each template is created through the ordinary endpoint and read back through the ordinary
     * endpoint. Equality of the two documents is what "indistinguishable from a hand-written
     * policy" means in practice, and it is also what lets the form editor (US42) load a policy,
     * change one field and save it.
     */
    @Test
    void everyTemplateSurvivesTheRoundTripThroughCreateAndRead() throws Exception {
        String root = signupAndLogin("tpl-roundtrip@nora.dev", "Root");

        JsonNode templates = read(exchange(HttpMethod.GET, "/iam/policy-templates", null, root));

        assertThat(templates.size()).isPositive();
        for (JsonNode template : templates) {
            JsonNode sent = template.path("document");
            String policyId = createPolicy(root, template.path("id").asText(), sent);
            JsonNode stored =
                    read(exchange(HttpMethod.GET, "/iam/policies/" + policyId, null, root));
            assertThat(stored.path("document").toString())
                    .as("round trip of %s", template.path("id").asText())
                    .isEqualTo(sent.toString());
        }
    }

    /* ==================== the policy it produces ====================== */

    @Test
    void aPolicyCreatedFromATemplateDecidesRealRequests() throws Exception {
        String root = signupAndLogin("tpl-decides@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "tpl-decides-m@nora.dev", "Membro");
        JsonNode readOnly = templateById(root, "read-only-access");

        String policyId = createPolicy(root, "read-only-access", readOnly.path("document"));
        attach(root, member, policyId);

        String meeting = "nora:tenant/" + tenant + ":meeting/abc";
        JsonNode allowed = read(simulate(root, member, "meeting:read", meeting));
        assertThat(allowed.path("allowed").asBoolean()).isTrue();
        assertThat(allowed.path("policyName").asText()).isEqualTo("read-only-access");

        JsonNode denied = read(simulate(root, member, "meeting:update", meeting));
        assertThat(denied.path("allowed").asBoolean()).isFalse();
        assertThat(denied.path("reason").asText()).isEqualTo("NO_MATCHING_STATEMENT");
    }

    /**
     * The condition template ships a placeholder value, so it grants nothing until someone edits
     * it. Asserted end to end because the alternative — a template that allows everything until it
     * is narrowed — is the failure that would matter.
     */
    @Test
    void theConditionTemplateGrantsNothingUntilItsValueIsReplaced() throws Exception {
        String root = signupAndLogin("tpl-cond@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "tpl-cond-m@nora.dev", "Membro");
        JsonNode scoped = templateById(root, "department-scoped-meeting-reader");

        attach(root, member, createPolicy(root, "por-departamento", scoped.path("document")));

        String meeting = "nora:tenant/" + tenant + ":meeting/abc";
        Map<String, String> real = Map.of("department", "Vendas");
        JsonNode answer = read(simulate(root, member, "meeting:read", meeting, real));

        assertThat(answer.path("allowed").asBoolean()).isFalse();
        assertThat(answer.path("reason").asText()).isEqualTo("NO_MATCHING_STATEMENT");
        assertThat(answer.path("statementsEvaluated").asInt()).isEqualTo(1);
    }

    /* ========================== the permission ======================== */

    /**
     * The catalogue is a constant that says nothing about the tenant, so it rides on {@code
     * iam:policy:read} instead of an action of its own. It is still gated: a member with no policy
     * attached reads nothing.
     */
    @Test
    void readingTheCatalogueRequiresThePolicyReadAction() throws Exception {
        String root = signupAndLogin("tpl-perm@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "tpl-perm-m@nora.dev", "Membro");
        String member = login("tpl-perm-m@nora.dev");

        ResponseEntity<String> refused =
                exchange(HttpMethod.GET, "/iam/policy-templates", null, member);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        attach(root, memberId, createPolicy(root, "le-policies", readsPolicies(tenant)));
        JsonNode templates = read(exchange(HttpMethod.GET, "/iam/policy-templates", null, member));
        assertThat(templates.size()).isPositive();
    }

    /* ============================= fixtures =========================== */

    private JsonNode readsPolicies(UUID tenantId) throws Exception {
        String arn = "nora:tenant/" + tenantId + ":iam/*";
        String doc =
                "{\"version\":\"2026-05-07\",\"statements\":[{\"effect\":\"Allow\","
                        + "\"action\":[\"iam:policy:read\"],\"resource\":[\""
                        + arn
                        + "\"]}]}";
        return mapper.readTree(doc);
    }

    private JsonNode templateById(String token, String id) throws Exception {
        JsonNode templates = read(exchange(HttpMethod.GET, "/iam/policy-templates", null, token));
        for (JsonNode template : templates) {
            if (id.equals(template.path("id").asText())) {
                return template;
            }
        }
        throw new AssertionError("no template with id " + id);
    }

    /* ============================= helpers ============================ */

    private ResponseEntity<String> simulate(
            String token, UUID userId, String action, String resource) throws Exception {
        return simulate(token, userId, action, resource, Map.of());
    }

    private ResponseEntity<String> simulate(
            String token, UUID userId, String action, String resource, Map<String, String> ctx)
            throws Exception {
        Map<String, Object> body =
                Map.of("userId", userId, "action", action, "resource", resource, "context", ctx);
        return exchange(HttpMethod.POST, "/iam/simulate", json(body), token);
    }

    private String createPolicy(String token, String name, JsonNode document) throws Exception {
        String body = json(Map.of("name", name, "document", document));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/iam/policies", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private void attach(String token, UUID userId, String policyId) throws Exception {
        String path = "/iam/users/" + userId + "/policies/" + policyId;
        read(exchange(HttpMethod.POST, path, null, token), HttpStatus.NO_CONTENT);
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

    private JsonNode read(ResponseEntity<String> resp) throws Exception {
        return read(resp, HttpStatus.OK);
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

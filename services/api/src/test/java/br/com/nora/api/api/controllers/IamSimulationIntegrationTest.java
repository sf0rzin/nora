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
 * US43 — {@code POST /iam/simulate}: the decision the authorization pipeline would take, with the
 * statement that took it, without performing the operation.
 *
 * <p>Two properties carry this endpoint. The first is that the answer explains itself: a Root is
 * reported as a bypass instead of a mute {@code true}, and every other decision names the policy
 * and the statement behind it. The second is the tenant boundary — the {@code userId} in the body
 * is an id the caller chose, so it is resolved inside the caller's own tenant and anything else is
 * a 404. Answering "deny, no statements" for a stranger would be true and would still confirm the
 * id exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamSimulationIntegrationTest {

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

    /* ======================= the tenant boundary ====================== */

    @Test
    void simulatingAUserOfAnotherTenantLeaksNoDecision() throws Exception {
        String rootA = signupAndLogin("sim-cross-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(rootA, "tenantId");
        String rootB = signupAndLogin("sim-cross-b@nora.dev", "Beta");
        UUID tenantB = readClaim(rootB, "tenantId");
        UUID memberB = insertActiveMember(tenantB, "sim-cross-bm@nora.dev", "Membro Beta");

        ResponseEntity<String> resp = simulate(rootA, memberB, "meeting:read", meetings(tenantA));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.path("code").asText()).isEqualTo("IAM_USER_NOT_FOUND");
        assertThat(body.path("allowed").isMissingNode()).isTrue();
        assertThat(body.path("reason").isMissingNode()).isTrue();
        assertThat(body.path("statement").isMissingNode()).isTrue();

        // Positive control: the same call against a user of the caller's own tenant answers.
        UUID memberA = insertActiveMember(tenantA, "sim-cross-am@nora.dev", "Membro Alfa");
        JsonNode own = read(simulate(rootA, memberA, "meeting:read", meetings(tenantA)));
        assertThat(own.path("allowed").asBoolean()).isFalse();
    }

    /* =========================== the answer =========================== */

    @Test
    void rootIsReportedAsABypassInsteadOfAMuteTrue() throws Exception {
        String root = signupAndLogin("sim-root@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID rootId = readClaim(root, "sub");

        JsonNode answer = read(simulate(root, rootId, "meeting:read", meetings(tenant)));

        assertThat(answer.path("allowed").asBoolean()).isTrue();
        assertThat(answer.path("reason").asText()).isEqualTo("ROOT_BYPASS");
        assertThat(answer.path("statement").isNull()).isTrue();
        assertThat(answer.path("policyName").isNull()).isTrue();
        assertThat(answer.path("statementsEvaluated").asInt()).isZero();
    }

    @Test
    void aSimpleAllowNamesThePolicyAndTheStatement() throws Exception {
        String root = signupAndLogin("sim-allow@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "sim-allow-m@nora.dev", "Membro");
        String doc = document("Allow", "meeting:read", meetings(tenant), null);
        attach(root, member, createPolicy(root, "leitura-de-reunioes", doc));

        JsonNode answer = read(simulate(root, member, "meeting:read", oneMeeting(tenant, "abc")));

        assertThat(answer.path("allowed").asBoolean()).isTrue();
        assertThat(answer.path("reason").asText()).isEqualTo("ALLOW");
        assertThat(answer.path("policyName").asText()).isEqualTo("leitura-de-reunioes");
        assertThat(answer.path("statementIndex").asInt()).isZero();
        assertThat(answer.path("statement").path("effect").asText()).isEqualTo("ALLOW");
        assertThat(answer.path("statementsEvaluated").asInt()).isEqualTo(1);
    }

    @Test
    void anExplicitDenyBeatsTheAllowAndIsTheStatementReported() throws Exception {
        String root = signupAndLogin("sim-deny@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "sim-deny-m@nora.dev", "Membro");
        String wide = document("Allow", "meeting:*", meetings(tenant), null);
        String narrow = document("Deny", "meeting:read", oneMeeting(tenant, "abc"), null);
        attach(root, member, createPolicy(root, "a-tudo-em-reunioes", wide));
        attach(root, member, createPolicy(root, "b-nega-uma-reuniao", narrow));

        JsonNode denied = read(simulate(root, member, "meeting:read", oneMeeting(tenant, "abc")));

        assertThat(denied.path("allowed").asBoolean()).isFalse();
        assertThat(denied.path("reason").asText()).isEqualTo("EXPLICIT_DENY");
        assertThat(denied.path("policyName").asText()).isEqualTo("b-nega-uma-reuniao");
        assertThat(denied.path("statement").path("effect").asText()).isEqualTo("DENY");

        // The Allow still decides every other meeting, and the answer names the other policy.
        JsonNode wider = read(simulate(root, member, "meeting:read", oneMeeting(tenant, "xyz")));
        assertThat(wider.path("allowed").asBoolean()).isTrue();
        assertThat(wider.path("policyName").asText()).isEqualTo("a-tudo-em-reunioes");
    }

    @Test
    void theSameConditionSatisfiedAndUnsatisfied() throws Exception {
        String root = signupAndLogin("sim-cond@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "sim-cond-m@nora.dev", "Membro");
        String cond = "{\"StringEquals\":{\"department\":\"Vendas\"}}";
        String doc = document("Allow", "meeting:read", meetings(tenant), cond);
        attach(root, member, createPolicy(root, "so-vendas", doc));

        String arn = oneMeeting(tenant, "abc");
        Map<String, String> vendas = Map.of("department", "Vendas");
        Map<String, String> suporte = Map.of("department", "Suporte");

        JsonNode satisfied = read(simulate(root, member, "meeting:read", arn, vendas));
        assertThat(satisfied.path("allowed").asBoolean()).isTrue();
        assertThat(satisfied.path("policyName").asText()).isEqualTo("so-vendas");

        JsonNode unsatisfied = read(simulate(root, member, "meeting:read", arn, suporte));
        assertThat(unsatisfied.path("allowed").asBoolean()).isFalse();
        assertThat(unsatisfied.path("reason").asText()).isEqualTo("NO_MATCHING_STATEMENT");
        assertThat(unsatisfied.path("statement").isNull()).isTrue();
        assertThat(unsatisfied.path("statementsEvaluated").asInt()).isEqualTo(1);
    }

    /**
     * An operator the evaluator does not implement makes the statement not match, so an Allow
     * carrying it grants nothing. The simulation has to show the same thing, or it would send
     * whoever is debugging looking for a bug in the attachment.
     */
    @Test
    void anUnsupportedOperatorIsFailClosedInTheSimulationToo() throws Exception {
        String root = signupAndLogin("sim-op@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID member = insertActiveMember(tenant, "sim-op-m@nora.dev", "Membro");
        String cond = "{\"StringNotEquals\":{\"department\":\"Suporte\"}}";
        String doc = document("Allow", "meeting:read", meetings(tenant), cond);
        attach(root, member, createPolicy(root, "operador-futuro", doc));

        String arn = oneMeeting(tenant, "abc");
        Map<String, String> ctx = Map.of("department", "Vendas");
        JsonNode answer = read(simulate(root, member, "meeting:read", arn, ctx));

        assertThat(answer.path("allowed").asBoolean()).isFalse();
        assertThat(answer.path("reason").asText()).isEqualTo("NO_MATCHING_STATEMENT");
        assertThat(answer.path("statementsEvaluated").asInt()).isEqualTo(1);
    }

    /* ========================== the permission ======================== */

    /**
     * The endpoint has its own action. Reading the policies is not enough, because the answer is
     * derived from which policies are ATTACHED to which user and no endpoint exposes that graph.
     */
    @Test
    void policyReadAloneDoesNotAllowSimulating() throws Exception {
        String root = signupAndLogin("sim-perm@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "sim-perm-m@nora.dev", "Membro");
        String member = login("sim-perm-m@nora.dev");
        String arn = iamArn(tenant);
        attach(root, memberId, createPolicy(root, "le-policies", readsPolicies(arn)));

        ResponseEntity<String> refused = simulate(member, memberId, "meeting:read", arn);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // With the simulate action granted, the very same call answers.
        attach(root, memberId, createPolicy(root, "simula", simulates(arn)));
        JsonNode answer = read(simulate(member, memberId, "meeting:read", meetings(tenant)));
        assertThat(answer.path("allowed").asBoolean()).isFalse();
        assertThat(answer.path("reason").asText()).isEqualTo("NO_MATCHING_STATEMENT");
    }

    /* ============================= fixtures =========================== */

    private static String meetings(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":meeting/*";
    }

    private static String oneMeeting(UUID tenantId, String id) {
        return "nora:tenant/" + tenantId + ":meeting/" + id;
    }

    private static String iamArn(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":iam/*";
    }

    private static String readsPolicies(String arn) {
        return document("Allow", "iam:policy:read", arn, null);
    }

    private static String simulates(String arn) {
        return document("Allow", "iam:policy:simulate", arn, null);
    }

    /** A one-statement policy document; {@code condition} is raw JSON, or null for none. */
    private static String document(String effect, String action, String res, String condition) {
        String cond = condition == null ? "" : ",\"condition\":" + condition;
        String stmt = "{\"effect\":\"" + effect + "\",\"action\":[\"" + action + "\"],";
        stmt = stmt + "\"resource\":[\"" + res + "\"]" + cond + "}";
        return "{\"version\":\"2026-05-07\",\"statements\":[" + stmt + "]}";
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

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        String body = json(Map.of("name", name, "document", mapper.readTree(documentJson)));
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

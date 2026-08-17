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
 * US44 — permission boundaries, end to end through the real gate.
 *
 * <p>{@link br.com.nora.api.domain.iam.PolicyEvaluatorBoundaryTest} proves the intersection at the
 * decision point. This file proves the parts an evaluator test cannot: that the cap reaches the
 * HTTP gate and not only the simulator, that it is written and read inside one tenant, that nobody
 * edits their own, and that the endpoints capping IAM are themselves capped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamPermissionBoundaryIntegrationTest {

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

    /* ==================== the cap reaches the real gate =================== */

    /**
     * The member holds {@code iam:*} and the boundary permits only {@code iam:policy:read}. Reading
     * policies still works and simulating stops working — the same principal, the same attached
     * policy, one action lost to the cap.
     */
    @Test
    void theBoundaryCapsWhatTheAttachedPolicyGrants() throws Exception {
        String root = signupAndLogin("pb-cap@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-cap-m@nora.dev", "Membro");
        String member = login("pb-cap-m@nora.dev");
        String arn = iamArn(tenant);
        attach(root, memberId, createPolicy(root, "iam-total", allowOf("iam:*", arn)));

        assertThat(get(member, "/iam/policies").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simulate(member, memberId, "iam:policy:read", arn).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        String cap = createPolicy(root, "so-leitura-de-policies", allowOf("iam:policy:read", arn));
        setBoundary(root, memberId, cap, HttpStatus.NO_CONTENT);

        assertThat(get(member, "/iam/policies").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(simulate(member, memberId, "iam:policy:read", arn).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * The test that separates a cap from a second grant path: the widest boundary in the tenant on
     * a user with nothing attached still allows nothing.
     */
    @Test
    void aBoundaryNeverGrants() throws Exception {
        String root = signupAndLogin("pb-grant@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-grant-m@nora.dev", "Membro");
        String member = login("pb-grant-m@nora.dev");
        String everything = createPolicy(root, "tudo", allowOf("*", "*"));

        setBoundary(root, memberId, everything, HttpStatus.NO_CONTENT);

        assertThat(get(member, "/iam/policies").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A user with no boundary answers 200 with an empty one, and behaves exactly as before. */
    @Test
    void aUserWithNoBoundaryIsUnaffected() throws Exception {
        String root = signupAndLogin("pb-none@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-none-m@nora.dev", "Membro");
        String member = login("pb-none-m@nora.dev");
        String arn = iamArn(tenant);
        attach(root, memberId, createPolicy(root, "iam-total", allowOf("iam:*", arn)));

        JsonNode empty = read(get(root, "/iam/users/" + memberId + "/boundary"));
        assertThat(empty.path("userId").asText()).isEqualTo(memberId.toString());
        assertThat(empty.path("policyId").isNull()).isTrue();
        assertThat(empty.path("policyName").isNull()).isTrue();

        assertThat(get(member, "/iam/policies").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode answer = read(simulate(root, memberId, "iam:policy:read", arn));
        assertThat(answer.path("allowed").asBoolean()).isTrue();
        assertThat(answer.path("reason").asText()).isEqualTo("ALLOW");
        assertThat(answer.path("boundaryPolicyName").isNull()).isTrue();
    }

    /* ================== the simulator reports the cap ================== */

    @Test
    void theSimulatorNamesTheBoundaryWhenTheBoundaryIsWhatDenied() throws Exception {
        String root = signupAndLogin("pb-sim@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-sim-m@nora.dev", "Membro");
        String meetings = meetings(tenant);
        attach(root, memberId, createPolicy(root, "reunioes", allowOf("meeting:*", meetings)));
        String cap = createPolicy(root, "limite-leitura", allowOf("meeting:read", meetings));
        setBoundary(root, memberId, cap, HttpStatus.NO_CONTENT);

        JsonNode capped = read(simulate(root, memberId, "meeting:update", oneMeeting(tenant)));
        assertThat(capped.path("allowed").asBoolean()).isFalse();
        assertThat(capped.path("reason").asText()).isEqualTo("BOUNDARY_NOT_PERMITTED");
        assertThat(capped.path("boundaryPolicyName").asText()).isEqualTo("limite-leitura");
        assertThat(capped.path("statement").isNull()).isTrue();

        // Inside the cap the answer is the ordinary one, and it still names the cap that exists.
        JsonNode allowed = read(simulate(root, memberId, "meeting:read", oneMeeting(tenant)));
        assertThat(allowed.path("allowed").asBoolean()).isTrue();
        assertThat(allowed.path("reason").asText()).isEqualTo("ALLOW");
        assertThat(allowed.path("policyName").asText()).isEqualTo("reunioes");
        assertThat(allowed.path("boundaryPolicyName").asText()).isEqualTo("limite-leitura");
    }

    @Test
    void anExplicitDenyInTheBoundaryIsReportedWithItsOwnStatement() throws Exception {
        String root = signupAndLogin("pb-deny@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-deny-m@nora.dev", "Membro");
        String meetings = meetings(tenant);
        attach(root, memberId, createPolicy(root, "reunioes", allowOf("meeting:*", meetings)));
        String wide = allowStatement("*", "*");
        String doc = twoStatements(wide, denyStatement("meeting:read", meetings));
        String cap = createPolicy(root, "limite-com-deny", doc);
        setBoundary(root, memberId, cap, HttpStatus.NO_CONTENT);

        JsonNode denied = read(simulate(root, memberId, "meeting:read", oneMeeting(tenant)));

        assertThat(denied.path("allowed").asBoolean()).isFalse();
        assertThat(denied.path("reason").asText()).isEqualTo("BOUNDARY_EXPLICIT_DENY");
        assertThat(denied.path("policyName").asText()).isEqualTo("limite-com-deny");
        assertThat(denied.path("boundaryPolicyName").asText()).isEqualTo("limite-com-deny");
        assertThat(denied.path("statementIndex").asInt()).isEqualTo(1);
        assertThat(denied.path("statement").path("effect").asText()).isEqualTo("DENY");
    }

    /* ========================== the tenant boundary ======================== */

    @Test
    void theBoundaryOfAUserInAnotherTenantIsNeitherReadableNorWritable() throws Exception {
        String rootA = signupAndLogin("pb-cross-a@nora.dev", "Alfa");
        String rootB = signupAndLogin("pb-cross-b@nora.dev", "Beta");
        UUID tenantB = readClaim(rootB, "tenantId");
        UUID memberB = insertActiveMember(tenantB, "pb-cross-bm@nora.dev", "Membro Beta");
        String policyA = createPolicy(rootA, "policy-do-a", allowOf("*", "*"));

        String path = "/iam/users/" + memberB + "/boundary";
        assertThat(get(rootA, path).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        setBoundary(rootA, memberB, policyA, HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.DELETE, path, null, rootA).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Nothing was written: B's own Root still sees no boundary on its member.
        JsonNode fromB = read(get(rootB, path));
        assertThat(fromB.path("policyId").isNull()).isTrue();
    }

    /* ===================== who may set one ===================== */

    /** A principal that can widen its own cap has none. Both write verbs refuse the caller. */
    @Test
    void nobodyManagesItsOwnBoundary() throws Exception {
        String root = signupAndLogin("pb-self@nora.dev", "Root");
        UUID rootId = readClaim(root, "sub");
        String policy = createPolicy(root, "qualquer", allowOf("*", "*"));

        ResponseEntity<String> set = rawSetBoundary(root, rootId, policy);
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(set.getBody()).path("code").asText())
                .isEqualTo("IAM_BOUNDARY_SELF");

        String path = "/iam/users/" + rootId + "/boundary";
        ResponseEntity<String> removed = exchange(HttpMethod.DELETE, path, null, root);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(removed.getBody()).path("code").asText())
                .isEqualTo("IAM_BOUNDARY_SELF");
        // The refusal comes before the tenant Root rule, which would also have applied here.
    }

    /** The Root bypass ignores statements, so a boundary on the Root is refused, not stored. */
    @Test
    void theTenantRootCannotBeCapped() throws Exception {
        String root = signupAndLogin("pb-root@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID rootId = readClaim(root, "sub");
        UUID memberId = insertActiveMember(tenant, "pb-root-m@nora.dev", "Membro");
        String member = login("pb-root-m@nora.dev");
        String arn = iamArn(tenant);
        attach(root, memberId, createPolicy(root, "iam-total", allowOf("iam:*", arn)));
        String policy = createPolicy(root, "qualquer", allowOf("*", "*"));

        ResponseEntity<String> refused = rawSetBoundary(member, rootId, policy);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(refused.getBody()).path("code").asText())
                .isEqualTo("IAM_BOUNDARY_ON_ROOT");
    }

    /**
     * The delegation property, and the reason the self-refusal above is a second line rather than
     * the first: a boundary applies to {@code iam:boundary:*} like any other action, so a bounded
     * admin cannot touch anyone's cap — not even a subordinate's.
     */
    @Test
    void aBoundedAdminCannotReachTheBoundaryEndpointsAtAll() throws Exception {
        String root = signupAndLogin("pb-deleg@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID adminId = insertActiveMember(tenant, "pb-deleg-a@nora.dev", "Admin");
        UUID otherId = insertActiveMember(tenant, "pb-deleg-o@nora.dev", "Outro");
        String admin = login("pb-deleg-a@nora.dev");
        String arn = iamArn(tenant);
        attach(root, adminId, createPolicy(root, "iam-total", allowOf("iam:*", arn)));
        String policy = createPolicy(root, "qualquer", allowOf("*", "*"));

        // With no cap the delegated admin manages the other user's boundary.
        setBoundary(admin, otherId, policy, HttpStatus.NO_CONTENT);

        // Capped to the policy endpoints, the same admin loses the boundary endpoints entirely.
        String cap = createPolicy(root, "sem-boundary", allowOf("iam:policy:*", arn));
        setBoundary(root, adminId, cap, HttpStatus.NO_CONTENT);

        assertThat(rawSetBoundary(admin, otherId, policy).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        String path = "/iam/users/" + otherId + "/boundary";
        assertThat(exchange(HttpMethod.DELETE, path, null, admin).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(admin, path).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The Root, which no boundary caps, still can.
        assertThat(get(root, path).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /* ================== deleting the policy behind a cap ================== */

    /**
     * Deleting a policy that is somebody's cap would remove the cap through an endpoint whose audit
     * entry says only "policy deleted". V033 refuses it at the FK and the API says why.
     */
    @Test
    void aPolicyInUseAsABoundaryCannotBeDeleted() throws Exception {
        String root = signupAndLogin("pb-del@nora.dev", "Root");
        UUID tenant = readClaim(root, "tenantId");
        UUID memberId = insertActiveMember(tenant, "pb-del-m@nora.dev", "Membro");
        String cap = createPolicy(root, "limite", allowOf("meeting:read", meetings(tenant)));
        setBoundary(root, memberId, cap, HttpStatus.NO_CONTENT);

        ResponseEntity<String> refused =
                exchange(HttpMethod.DELETE, "/iam/policies/" + cap, null, root);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(refused.getBody()).path("code").asText())
                .isEqualTo("IAM_POLICY_IN_USE_AS_BOUNDARY");

        // Remove the boundary and the very same delete succeeds.
        String path = "/iam/users/" + memberId + "/boundary";
        assertThat(exchange(HttpMethod.DELETE, path, null, root).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(HttpMethod.DELETE, "/iam/policies/" + cap, null, root).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /* ============================= fixtures =========================== */

    private static String meetings(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":meeting/*";
    }

    private static String oneMeeting(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":meeting/abc";
    }

    private static String iamArn(UUID tenantId) {
        return "nora:tenant/" + tenantId + ":iam/*";
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"effect\":\""
                + effect
                + "\",\"action\":[\""
                + action
                + "\"],\"resource\":[\""
                + resource
                + "\"]}";
    }

    private static String allowStatement(String action, String resource) {
        return statement("Allow", action, resource);
    }

    private static String denyStatement(String action, String resource) {
        return statement("Deny", action, resource);
    }

    private static String allowOf(String action, String resource) {
        return twoStatements(allowStatement(action, resource), null);
    }

    /** A document with one or two statements; {@code second} may be null for a single one. */
    private static String twoStatements(String first, String second) {
        String body = second == null ? first : first + "," + second;
        return "{\"version\":\"2026-05-07\",\"statements\":[" + body + "]}";
    }

    /* ============================= helpers ============================ */

    private void setBoundary(String token, UUID userId, String policyId, HttpStatus expected)
            throws Exception {
        assertThat(rawSetBoundary(token, userId, policyId).getStatusCode()).isEqualTo(expected);
    }

    private ResponseEntity<String> rawSetBoundary(String token, UUID userId, String policyId)
            throws Exception {
        String body = json(Map.of("policyId", policyId));
        return exchange(HttpMethod.PUT, "/iam/users/" + userId + "/boundary", body, token);
    }

    private ResponseEntity<String> simulate(String token, UUID userId, String action, String res)
            throws Exception {
        Map<String, Object> body =
                Map.of("userId", userId, "action", action, "resource", res, "context", Map.of());
        return exchange(HttpMethod.POST, "/iam/simulate", json(body), token);
    }

    private ResponseEntity<String> get(String token, String path) {
        return exchange(HttpMethod.GET, path, null, token);
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

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
 * Cross-tenant isolation of the IAM configuration: groups, policies, their attachments and the
 * audit trail. Sibling of {@code IamScopingIntegrationTest}, which is entirely INTRA-tenant (the
 * Root bypass versus a member's policy conditions) and therefore says nothing about the boundary
 * between two workspaces.
 *
 * <p>This is the aggregate where the gap bites hardest. Migration V020 deliberately DISABLED row
 * level security on thirteen tables, seven of them the IAM authorization tables, because onboarding
 * flows write to them with no JWT and therefore no tenant GUC to enforce against. The policies
 * stayed defined but inert, and the migration says so in as many words: isolation there continues
 * via the tenant_id filter in the application. There is no second layer. If a scoped query loses
 * its predicate, one tenant edits another tenant's permissions.
 *
 * <p>The intruder is the Root of tenant B. That is deliberate: since #407 authorization is
 * deny-by-default, so a principal with no policies is refused before tenant scoping is ever
 * consulted and the test would pass even with the tenant filter deleted. Root short-circuits
 * {@code AuthorizationService.isAllowed} before the evaluator runs, so every IAM action is
 * permitted to it — inside its own tenant. And the ARN the interceptor authorizes against is
 * {@code nora:tenant/{caller}:iam/*}, always built from the caller's own tenant claim, so the gate
 * is satisfied no matter which resource id the path carries. Whatever refuses these calls is the
 * tenant_id predicate and nothing else.
 *
 * <p>Reads and single-resource mutations answer 404, never 403 — a 403 would confirm that the group
 * or policy exists in another tenant. The two detach handlers are the exception and are asserted
 * differently; see the comment at that assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamIsolationIntegrationTest {

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

    /* ============================== groups ============================ */

    @Test
    void groups_ofAnotherTenant_areInvisibleAndUnreachable() throws Exception {
        String tokenA = signupAndLogin("iam-iso-grp-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(tokenA, "tenantId");
        String groupA = createGroup(tokenA, "Diretoria do Alfa");
        UUID memberA = insertActiveMember(tenantA, "iam-iso-grp-a-m@nora.dev", "Membro Alfa");
        addMember(tokenA, groupA, memberA);

        String tokenB = signupAndLogin("iam-iso-grp-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");
        String groupB = createGroup(tokenB, "Diretoria do Beta");
        UUID memberB = insertActiveMember(tenantB, "iam-iso-grp-b-m@nora.dev", "Membro Beta");

        // The listing is scoped both ways: neither tenant sees the other's group.
        assertThat(groupIds(tokenA)).containsExactly(groupA);
        assertThat(groupIds(tokenB)).containsExactly(groupB);

        String pathA = "/iam/groups/" + groupA;
        String membersA = pathA + "/members";
        String addToA = membersA + "/" + memberB;
        String dropFromA = membersA + "/" + memberA;

        assertThat(getStatus(membersA, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(post(addToA, null, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(pathA, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Removing a member is an unconditional tenant-scoped DELETE, so it answers 204 even
        // when it matched nothing. That is not a leak (the answer is 204 either way), and the
        // property that has to hold is the next assertion: A's membership survived it.
        assertThat(delete(dropFromA, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);

        // Refused means untouched: A still owns the group, with its member still inside.
        JsonNode members = read(authGet(membersA, tokenA), HttpStatus.OK);
        assertThat(members).hasSize(1);
        assertThat(members.get(0).asText()).isEqualTo(memberA.toString());
        assertThat(groupIds(tokenA)).containsExactly(groupA);

        // Positive control: the same three handlers work for B on B's own group.
        String pathB = "/iam/groups/" + groupB;
        String membersB = pathB + "/members";
        String addToB = membersB + "/" + memberB;
        assertThat(getStatus(membersB, tokenB)).isEqualTo(HttpStatus.OK);
        assertThat(post(addToB, null, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(pathB, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /* ============================= policies =========================== */

    @Test
    void policies_ofAnotherTenant_areInvisibleAndImmutable() throws Exception {
        String tokenA = signupAndLogin("iam-iso-pol-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(tokenA, "tenantId");
        String policyA = createPolicy(tokenA, "Leitura do Alfa", readMeetings(tenantA));

        String tokenB = signupAndLogin("iam-iso-pol-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");
        String docB = readMeetings(tenantB);
        String policyB = createPolicy(tokenB, "Leitura do Beta", docB);

        assertThat(policyIds(tokenA)).containsExactly(policyA);
        assertThat(policyIds(tokenB)).containsExactly(policyB);

        String pathA = "/iam/policies/" + policyA;
        String pathB = "/iam/policies/" + policyB;
        String rewrite = json(Map.of("document", mapper.readTree(docB)));
        JsonNode before = read(authGet(pathA, tokenA), HttpStatus.OK);
        int versionBefore = before.get("currentVersion").asInt();

        assertThat(getStatus(pathA, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put(pathA, rewrite, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(pathA, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Refused means untouched: the policy is still there and was never re-versioned. A
        // successful rewrite would have bumped currentVersion, so this is the real proof.
        JsonNode survived = read(authGet(pathA, tokenA), HttpStatus.OK);
        assertThat(survived.get("name").asText()).isEqualTo("Leitura do Alfa");
        assertThat(survived.get("currentVersion").asInt()).isEqualTo(versionBefore);

        // Positive control: the same three handlers work for B on B's own policy.
        assertThat(getStatus(pathB, tokenB)).isEqualTo(HttpStatus.OK);
        assertThat(put(pathB, rewrite, tokenB)).isEqualTo(HttpStatus.OK);
        assertThat(delete(pathB, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /* =========================== attachments ========================== */

    @Test
    void attachments_cannotCrossTheTenantBoundary() throws Exception {
        String tokenA = signupAndLogin("iam-iso-att-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(tokenA, "tenantId");
        UUID rootA = readClaim(tokenA, "sub");
        String groupA = createGroup(tokenA, "Grupo do Alfa");
        String policyA = createPolicy(tokenA, "Politica do Alfa", readMeetings(tenantA));
        attachToGroup(tokenA, groupA, policyA);

        String tokenB = signupAndLogin("iam-iso-att-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");
        UUID rootB = readClaim(tokenB, "sub");
        String groupB = createGroup(tokenB, "Grupo do Beta");
        String policyB = createPolicy(tokenB, "Politica do Beta", readMeetings(tenantB));

        // A's group with A's policy: the group lookup is tenant-scoped and refuses first.
        String foreignPair = groupPolicyPath(groupA, policyA);
        assertThat(post(foreignPair, null, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // B's own group with A's policy: now it is the policy lookup that refuses.
        String mixedPair = groupPolicyPath(groupB, policyA);
        assertThat(post(mixedPair, null, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // A's policy onto a user: same tenant-scoped policy lookup, same answer.
        String foreignUserPolicy = userPolicyPath(rootA, policyA);
        assertThat(post(foreignUserPolicy, null, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Detaching is an unconditional tenant-scoped DELETE and answers 204 whether or not it
        // matched anything, so the status carries no information either way. What has to hold
        // is that A's attachment is still in place afterwards.
        assertThat(delete(foreignPair, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countGroupPolicy(groupA, policyA)).isEqualTo(1);

        // Positive control: within B, both attach handlers and the detach succeed.
        String ownPair = groupPolicyPath(groupB, policyB);
        String ownUserPolicy = userPolicyPath(rootB, policyB);
        assertThat(post(ownPair, null, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(post(ownUserPolicy, null, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(ownPair, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * The remaining shape: B's own policy pointed at a user who lives in A. The application never
     * validates the target user against the caller's tenant — the composite foreign key added by
     * V027 is what stops it, so the refusal surfaces as a constraint translation rather than as a
     * domain error. The exact status is therefore not the interesting part and is asserted only as
     * "not a success"; the assertion that matters is that no row was written.
     */
    @Test
    void policyOfOneTenant_cannotBeAttachedToAUserOfAnother() throws Exception {
        String tokenA = signupAndLogin("iam-iso-fk-a@nora.dev", "Alfa");
        UUID rootA = readClaim(tokenA, "sub");

        String tokenB = signupAndLogin("iam-iso-fk-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");
        UUID rootB = readClaim(tokenB, "sub");
        String policyB = createPolicy(tokenB, "Politica do Beta", readMeetings(tenantB));

        String crossTenant = userPolicyPath(rootA, policyB);
        assertThat(post(crossTenant, null, tokenB).is2xxSuccessful()).isFalse();
        assertThat(countUserPolicy(rootA, policyB)).isZero();

        // Positive control: the same handler with a user of B's own tenant does write the row.
        String ownTenant = userPolicyPath(rootB, policyB);
        assertThat(post(ownTenant, null, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countUserPolicy(rootB, policyB)).isEqualTo(1);
    }

    /* ============================== audit ============================= */

    @Test
    void auditEvents_ofAnotherTenant_areNotReadable() throws Exception {
        String tokenA = signupAndLogin("iam-iso-aud-a@nora.dev", "Alfa");
        String groupA = createGroup(tokenA, "Grupo auditado do Alfa");

        String tokenB = signupAndLogin("iam-iso-aud-b@nora.dev", "Beta");
        String groupB = createGroup(tokenB, "Grupo auditado do Beta");

        // Positive: each tenant does see the event its own mutation recorded.
        assertThat(auditTargets(tokenA)).contains(groupA);
        assertThat(auditTargets(tokenB)).contains(groupB);

        // Negative: neither trail carries the other tenant's event.
        assertThat(auditTargets(tokenA)).doesNotContain(groupB);
        assertThat(auditTargets(tokenB)).doesNotContain(groupA);
    }

    /* ============================= fixtures =========================== */

    /** A minimal, valid policy document scoped to the given tenant's meetings. */
    private static String readMeetings(UUID tenantId) {
        String resource = "nora:tenant/" + tenantId + ":meeting/*";
        String stmt = "{\"effect\":\"Allow\",\"action\":[\"meeting:read\"],";
        stmt = stmt + "\"resource\":[\"" + resource + "\"]}";
        return "{\"version\":\"2026-05-07\",\"statements\":[" + stmt + "]}";
    }

    private static String groupPolicyPath(String groupId, String policyId) {
        return "/iam/groups/" + groupId + "/policies/" + policyId;
    }

    private static String userPolicyPath(UUID userId, String policyId) {
        return "/iam/users/" + userId + "/policies/" + policyId;
    }

    /* ============================= helpers ============================ */

    private String createGroup(String token, String name) throws Exception {
        String body = json(Map.of("name", name));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/iam/groups", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private void addMember(String token, String groupId, UUID userId) throws Exception {
        String path = "/iam/groups/" + groupId + "/members/" + userId;
        read(exchange(HttpMethod.POST, path, null, token), HttpStatus.NO_CONTENT);
    }

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        String body = json(Map.of("name", name, "document", mapper.readTree(documentJson)));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/iam/policies", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private void attachToGroup(String token, String groupId, String policyId) throws Exception {
        String path = groupPolicyPath(groupId, policyId);
        read(exchange(HttpMethod.POST, path, null, token), HttpStatus.NO_CONTENT);
    }

    private List<String> groupIds(String token) throws Exception {
        return idsOf(read(authGet("/iam/groups", token), HttpStatus.OK), "id");
    }

    private List<String> policyIds(String token) throws Exception {
        return idsOf(read(authGet("/iam/policies", token), HttpStatus.OK), "id");
    }

    private List<String> auditTargets(String token) throws Exception {
        return idsOf(read(authGet("/iam/audit?limit=200", token), HttpStatus.OK), "targetId");
    }

    private static List<String> idsOf(JsonNode array, String field) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path(field).asText()));
        return ids;
    }

    private int countGroupPolicy(String groupId, String policyId) {
        String sql = "SELECT count(*) FROM iam_group_policies WHERE group_id=? AND policy_id=?";
        UUID group = UUID.fromString(groupId);
        UUID policy = UUID.fromString(policyId);
        Integer count = jdbc.queryForObject(sql, Integer.class, group, policy);
        return count == null ? 0 : count;
    }

    private int countUserPolicy(UUID userId, String policyId) {
        String sql = "SELECT count(*) FROM iam_user_policies WHERE user_id=? AND policy_id=?";
        Integer count = jdbc.queryForObject(sql, Integer.class, userId, UUID.fromString(policyId));
        return count == null ? 0 : count;
    }

    private HttpStatusCode getStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private HttpStatusCode post(String path, String body, String token) {
        return exchange(HttpMethod.POST, path, body, token).getStatusCode();
    }

    private HttpStatusCode put(String path, String body, String token) {
        return exchange(HttpMethod.PUT, path, body, token).getStatusCode();
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

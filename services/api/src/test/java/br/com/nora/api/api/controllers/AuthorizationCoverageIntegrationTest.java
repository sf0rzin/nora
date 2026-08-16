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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Authorization coverage (#51). Sibling of {@code IamScopingIntegrationTest}, which proves the
 * scoping semantics; this one proves the COVERAGE: every authenticated, non-self endpoint refuses a
 * principal with no policies, the newly gated ones still work with the right permission, and a
 * handler that declares no authorization at all is refused by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthorizationCoverageIntegrationTest {

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
    @Autowired AnalysisService analysisService;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /* ========================= default deny ========================= */

    /**
     * The point of the default deny: a handler declaring neither the permission nor the justified
     * opt-out is refused, with a code that reads as a coding mistake rather than a policy denial.
     * The tenant Root is used on purpose — the refusal happens before the policy evaluator, so not
     * even the Root bypass reaches an undeclared handler.
     */
    @Test
    void handlerWithoutAnyAuthorizationDeclaration_isDenied() throws Exception {
        String rootToken = signupAndLogin("deny-default@nora.dev", "Root Default");

        ResponseEntity<String> resp = getRaw("/__authz-probe", rootToken);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mapper.readTree(resp.getBody()).get("code").asText())
                .isEqualTo("IAM_AUTHORIZATION_NOT_DECLARED");
    }

    /* ================== deny without any permission ================== */

    /** One assertion per authenticated, non-self endpoint: no policies means no access. */
    @Test
    void everyAuthenticatedEndpoint_deniesAPrincipalWithoutPolicies() throws Exception {
        String rootToken = signupAndLogin("coverage-root@nora.dev", "Coverage Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        String meeting = uploadMeeting(rootToken, "Discovery", Map.of(), TRANSCRIPT);
        String member = memberWithoutPolicies(tenantId, "coverage-member@nora.dev");

        String id = UUID.randomUUID().toString();
        Calls calls = new Calls();

        // meetings
        calls.get("/meetings");
        calls.get("/meetings/search?q=proposta");
        calls.get("/meetings/" + meeting);
        calls.put("/meetings/" + meeting + "/goal", goalBody());
        calls.delete("/meetings/" + meeting + "/goal");
        calls.post("/meetings/" + meeting + "/reprocess", null);
        calls.post("/meetings/live-analyze", json(Map.of("transcriptChunk", "Ana: ok.")));

        // tasks
        calls.get("/tasks");
        calls.patch("/tasks/" + id, json(Map.of("status", "DONE")));

        // workflows — before #51 these seven had no IAM gate at all
        calls.get("/workflows");
        calls.post("/workflows", emptyWorkflowBody());
        calls.get("/workflows/" + id);
        calls.put("/workflows/" + id, emptyWorkflowBody());
        calls.delete("/workflows/" + id);
        calls.post("/workflows/" + id + "/test", null);
        calls.get("/workflows/" + id + "/executions");

        // integrations — likewise, except the OAuth callback, which is public by design
        calls.get("/integrations");
        calls.post("/integrations/slack/oauth/start", null);
        calls.delete("/integrations/slack");
        calls.post("/integrations/telegram/pairing/start", null);
        calls.post("/integrations/telegram/pairing/verify", null);
        calls.post("/integrations/trello/token", json(Map.of("token", "abc")));

        // tenant settings and tenant context
        calls.get("/tenant");
        calls.put("/tenant/name", json(Map.of("name", "Novo nome")));
        calls.get("/tenant/domain");
        calls.put("/tenant/domain", json(Map.of("allowedEmailDomain", "nora.dev")));
        calls.get("/tenant/context");
        calls.put("/tenant/context", json(Map.of("companyName", "NORA")));

        // iam
        calls.get("/iam/groups");
        calls.post("/iam/groups", json(Map.of("name", "G")));
        calls.delete("/iam/groups/" + id);
        calls.get("/iam/groups/" + id + "/members");
        calls.post("/iam/groups/" + id + "/members/" + id, null);
        calls.delete("/iam/groups/" + id + "/members/" + id);
        calls.get("/iam/policies");
        calls.get("/iam/policies/" + id);
        calls.post("/iam/policies", json(Map.of("name", "P", "document", Map.of())));
        calls.put("/iam/policies/" + id, json(Map.of("document", Map.of())));
        calls.delete("/iam/policies/" + id);
        calls.post("/iam/groups/" + id + "/policies/" + id, null);
        calls.delete("/iam/groups/" + id + "/policies/" + id);
        calls.post("/iam/users/" + id + "/policies/" + id, null);
        calls.delete("/iam/users/" + id + "/policies/" + id);
        calls.get("/iam/audit");

        // invitations
        calls.post("/iam/users/invite", json(Map.of("email", "convidado@nora.dev")));
        calls.get("/iam/invites");
        calls.delete("/iam/invites/" + id);

        // privacy: a permanent hard-delete of the meeting and every piece of PII linked to it
        calls.delete("/privacy/meetings/" + meeting);

        for (Call c : calls.items) {
            assertThat(status(c, member))
                    .as("%s %s must be forbidden without policies", c.method(), c.path())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // The two multipart endpoints, which do not fit the JSON call shape above.
        assertThat(multipartStatus(member, "/meetings")).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(multipartStatus(member, "/meetings/split-preview"))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ===================== positive: workflows ====================== */

    /**
     * A deny-only test would also pass if the endpoint had been broken for everyone, so each newly
     * gated route is exercised with the permission granted.
     */
    @Test
    void workflows_areUsableByAMemberHoldingTheWorkflowPermissions() throws Exception {
        String rootToken = signupAndLogin("wf-root@nora.dev", "WF Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        UUID memberId = insertActiveMember(tenantId, "wf-member@nora.dev", "WF Member");
        String member = login("wf-member@nora.dev");
        grant(rootToken, memberId, tenantId, "workflow:*", ":workflow/*");

        ResponseEntity<String> created = exchange(post("/workflows", workflowBody()), member);
        assertThat(created.getStatusCode())
                .as("create body=%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String wf = mapper.readTree(created.getBody()).get("id").asText();

        assertThat(getStatus("/workflows", member)).isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/workflows/" + wf, member)).isEqualTo(HttpStatus.OK);
        assertThat(status(put("/workflows/" + wf, workflowBody()), member))
                .isEqualTo(HttpStatus.OK);
        assertThat(status(post("/workflows/" + wf + "/test", null), member))
                .isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/workflows/" + wf + "/executions", member)).isEqualTo(HttpStatus.OK);
        assertThat(status(delete("/workflows/" + wf), member)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * {@code workflow:test} is a separate action on purpose: running an automation fires its wired
     * actions for real (e-mail, Slack, issue creation) against the tenant's integrations, so
     * read-only access must not reach it.
     */
    @Test
    void workflowRead_doesNotAllowRunningTheWorkflow() throws Exception {
        String rootToken = signupAndLogin("wf-ro-root@nora.dev", "WF RO Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        UUID memberId = insertActiveMember(tenantId, "wf-ro-member@nora.dev", "WF RO");
        String member = login("wf-ro-member@nora.dev");
        grant(rootToken, memberId, tenantId, "workflow:read", ":workflow/*");

        ResponseEntity<String> created = exchange(post("/workflows", workflowBody()), rootToken);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String wf = mapper.readTree(created.getBody()).get("id").asText();

        assertThat(getStatus("/workflows/" + wf, member)).isEqualTo(HttpStatus.OK);
        assertThat(status(post("/workflows/" + wf + "/test", null), member))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(delete("/workflows/" + wf), member)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* =================== positive: integrations ===================== */

    @Test
    void integrations_areUsableByAMemberHoldingTheIntegrationPermissions() throws Exception {
        String rootToken = signupAndLogin("int-root@nora.dev", "Int Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        UUID memberId = insertActiveMember(tenantId, "int-member@nora.dev", "Int Member");
        String member = login("int-member@nora.dev");
        grant(rootToken, memberId, tenantId, "integration:*", ":integration/*");

        assertThat(getStatus("/integrations", member)).isEqualTo(HttpStatus.OK);
        // Disconnect is idempotent: with the permission it succeeds even with nothing connected.
        assertThat(status(delete("/integrations/slack"), member)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Reading the hub must not let a member connect or drop the workspace's integrations. */
    @Test
    void integrationRead_doesNotAllowMutatingConnections() throws Exception {
        String rootToken = signupAndLogin("int-ro-root@nora.dev", "Int RO Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        UUID memberId = insertActiveMember(tenantId, "int-ro-member@nora.dev", "Int RO");
        String member = login("int-ro-member@nora.dev");
        grant(rootToken, memberId, tenantId, "integration:read", ":integration/*");

        assertThat(getStatus("/integrations", member)).isEqualTo(HttpStatus.OK);
        assertThat(status(delete("/integrations/slack"), member)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /* ================ GET /tasks per-item filtering ================== */

    /**
     * The listing used to authorize once against the literal ARN {@code ...:task/*} and then return
     * the whole tenant. A Deny written against one task id has to remove that task from the listing
     * while leaving the rest visible.
     */
    @Test
    void taskListing_hidesATaskCoveredByASpecificDeny() throws Exception {
        String rootToken = signupAndLogin("tasks-root@nora.dev", "Tasks Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        String meeting = uploadMeeting(rootToken, "Kickoff", Map.of(), TRANSCRIPT);
        analysisService.run(UUID.fromString(meeting), tenantId);

        JsonNode all = mapper.readTree(getRaw("/tasks", rootToken).getBody());
        assertThat(all.get("items")).hasSize(2);
        String hidden = all.get("items").get(0).get("id").asText();
        String visible = all.get("items").get(1).get("id").asText();

        UUID memberId = insertActiveMember(tenantId, "tasks-member@nora.dev", "Tasks Member");
        String member = login("tasks-member@nora.dev");
        String document =
                document(
                        statement("Allow", "task:read", arn(tenantId, ":task/*"), null),
                        statement("Deny", "task:read", arn(tenantId, ":task/" + hidden), null));
        attach(rootToken, memberId, createPolicy(rootToken, "TaskDenyOne", document));

        JsonNode listing = mapper.readTree(getRaw("/tasks", member).getBody());
        List<String> ids = new ArrayList<>();
        listing.get("items").forEach(item -> ids.add(item.get("id").asText()));
        assertThat(ids).containsExactly(visible);
    }

    /* ======================== privacy erase ========================= */

    /**
     * The erase authorized on the meeting id alone, with an empty condition context. A condition
     * over an attribute missing from that context makes the statement not match, so the Deny was
     * dropped and the hard-delete went through. It now answers exactly like the destructive goal
     * removal, which has always passed the meeting's attributes.
     */
    @Test
    void privacyErase_honoursAnAttributeScopedDeny_likeTheGoalRemovalAlreadyDid() throws Exception {
        String rootToken = signupAndLogin("erase-root@nora.dev", "Erase Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        Map<String, String> attributes = Map.of("department", "Juridico");
        String meeting = uploadMeeting(rootToken, "Confidencial", attributes, TRANSCRIPT);

        UUID memberId = insertActiveMember(tenantId, "erase-member@nora.dev", "Erase Member");
        String member = login("erase-member@nora.dev");
        String condition = "{\"StringEquals\":{\"department\":\"Juridico\"}}";
        String scope = arn(tenantId, ":meeting/*");
        String document =
                document(
                        statement("Allow", "meeting:update", scope, null),
                        statement("Deny", "meeting:update", scope, condition));
        attach(rootToken, memberId, createPolicy(rootToken, "DenyJuridico", document));

        assertThat(status(delete("/meetings/" + meeting + "/goal"), member))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(delete("/privacy/meetings/" + meeting), member))
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Refused means untouched: the meeting and its PII are still there.
        assertThat(getStatus("/meetings/" + meeting, rootToken)).isEqualTo(HttpStatus.OK);
    }

    /** Another tenant's meeting still answers 404, never 403 — no cross-tenant existence leak. */
    @Test
    void privacyErase_ofAnotherTenantsMeeting_stillAnswersNotFound() throws Exception {
        String tokenA = signupAndLogin("erase-tenant-a@nora.dev", "A");
        String meeting = uploadMeeting(tokenA, "Do A", Map.of(), TRANSCRIPT);
        String tokenB = signupAndLogin("erase-tenant-b@nora.dev", "B");

        assertThat(status(delete("/privacy/meetings/" + meeting), tokenB))
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /* ================= self endpoints stay reachable ================= */

    /** The "self" opt-out is a real category, not a hole: those routes must keep working. */
    @Test
    void principalScopedEndpoints_remainReachableWithoutAnyPolicy() throws Exception {
        String rootToken = signupAndLogin("self-root@nora.dev", "Self Root");
        UUID tenantId = readClaim(rootToken, "tenantId");
        String member = memberWithoutPolicies(tenantId, "self-member@nora.dev");

        assertThat(getStatus("/auth/me", member)).isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/chat/sessions", member)).isEqualTo(HttpStatus.OK);
    }

    /* ============================ helpers =========================== */

    private static final String TRANSCRIPT = "Ana: vamos fechar.\nBruno: mando ate sexta.";

    private record Call(HttpMethod method, String path, String body) {}

    /** Small accumulator so each endpoint under test is one short, readable line. */
    private static final class Calls {
        final List<Call> items = new ArrayList<>();

        void get(String path) {
            items.add(new Call(HttpMethod.GET, path, null));
        }

        void post(String path, String body) {
            items.add(new Call(HttpMethod.POST, path, body));
        }

        void put(String path, String body) {
            items.add(new Call(HttpMethod.PUT, path, body));
        }

        void patch(String path, String body) {
            items.add(new Call(HttpMethod.PATCH, path, body));
        }

        void delete(String path) {
            items.add(new Call(HttpMethod.DELETE, path, null));
        }
    }

    private static Call post(String path, String body) {
        return new Call(HttpMethod.POST, path, body);
    }

    private static Call put(String path, String body) {
        return new Call(HttpMethod.PUT, path, body);
    }

    private static Call delete(String path) {
        return new Call(HttpMethod.DELETE, path, null);
    }

    private HttpStatusCode status(Call c, String token) {
        return exchange(c, token).getStatusCode();
    }

    private HttpStatusCode getStatus(String path, String token) {
        return getRaw(path, token).getStatusCode();
    }

    private ResponseEntity<String> exchange(Call c, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(c.body(), headers);
        return rest.exchange(c.path(), c.method(), entity, String.class);
    }

    private ResponseEntity<String> getRaw(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private String goalBody() throws Exception {
        return json(Map.of("purpose", "Fechar contrato", "expectedOutcomes", List.of("Proposta")));
    }

    private String emptyWorkflowBody() throws Exception {
        return json(Map.of("name", "W", "definition", Map.of()));
    }

    private String workflowBody() throws Exception {
        String definition =
                "{\"nodes\":[{\"id\":\"t1\",\"kind\":\"trigger\","
                        + "\"type\":\"meeting.analysis_completed\"},"
                        + "{\"id\":\"a1\",\"kind\":\"action\",\"type\":\"send_email\","
                        + "\"params\":{\"to\":\"dest@nora.dev\"}}],"
                        + "\"edges\":[{\"id\":\"e1\",\"source\":\"t1\",\"target\":\"a1\"}]}";
        return json(Map.of("name", "Notificar", "definition", mapper.readTree(definition)));
    }

    private static String arn(UUID tenantId, String suffix) {
        return "nora:tenant/" + tenantId + suffix;
    }

    private static String document(String... statements) {
        String body = String.join(",", statements);
        return "{\"version\":\"2026-05-07\",\"statements\":[" + body + "]}";
    }

    private static String statement(
            String effect, String action, String resource, String conditionJson) {
        String head =
                "{\"effect\":\""
                        + effect
                        + "\",\"action\":[\""
                        + action
                        + "\"],\"resource\":[\""
                        + resource
                        + "\"]";
        return conditionJson == null ? head + "}" : head + ",\"condition\":" + conditionJson + "}";
    }

    /** Single-statement Allow policy, attached directly to the member. */
    private void grant(String rootToken, UUID memberId, UUID tenantId, String action, String path)
            throws Exception {
        String document = document(statement("Allow", action, arn(tenantId, path), null));
        attach(rootToken, memberId, createPolicy(rootToken, "Allow " + action, document));
    }

    private String memberWithoutPolicies(UUID tenantId, String email) throws Exception {
        insertActiveMember(tenantId, email, "Member");
        return login(email);
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
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
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

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        String body = json(Map.of("name", name, "document", mapper.readTree(documentJson)));
        ResponseEntity<String> resp = exchange(post("/iam/policies", body), token);
        assertThat(resp.getStatusCode())
                .as("policy body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    private void attach(String token, UUID userId, String policyId) {
        Call c = post("/iam/users/" + userId + "/policies/" + policyId, null);
        ResponseEntity<String> resp = exchange(c, token);
        assertThat(resp.getStatusCode())
                .as("attach body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private String uploadMeeting(
            String token, String title, Map<String, String> attributes, String content)
            throws Exception {
        ResponseEntity<String> resp = multipart(token, "/meetings", title, attributes, content);
        assertThat(resp.getStatusCode())
                .as("upload body=%s", resp.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    private HttpStatusCode multipartStatus(String token, String path) throws Exception {
        return multipart(token, path, "Qualquer", Map.of(), TRANSCRIPT).getStatusCode();
    }

    private ResponseEntity<String> multipart(
            String token, String path, String title, Map<String, String> attributes, String content)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaHeaders = new HttpHeaders();
        metaHeaders.setContentType(MediaType.APPLICATION_JSON);
        String metadata =
                json(Map.of("title", title, "transcriptFormat", "TXT", "attributes", attributes));
        body.add("metadata", new HttpEntity<>(metadata, metaHeaders));

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource file =
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "transcript.txt";
                    }
                };
        body.add("file", new HttpEntity<>(file, fileHeaders));

        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private UUID readClaim(String jwt, String claim) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        String payload = new String(decoded, StandardCharsets.UTF_8);
        return UUID.fromString(mapper.readTree(payload).get(claim).asText());
    }

    /* ======================= test-scoped beans ====================== */

    @TestConfiguration
    static class TestBeans {

        /**
         * A handler declaring NEITHER the permission annotation NOR the justified opt-out — exactly
         * what the default deny exists to catch. It lives in the test sources so production code
         * gains no unreachable endpoint.
         */
        @Bean
        UndeclaredAuthorizationProbeController undeclaredAuthorizationProbe() {
            return new UndeclaredAuthorizationProbeController();
        }

        /** Deterministic worker stub: the analysis has to yield exactly two action items. */
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
                                    "Resumo stub para cobertura de autorizacao.",
                                    Sentiment.NEUTRAL,
                                    List.of("cobertura"),
                                    List.of(),
                                    List.of(
                                            ActionItem.fresh(
                                                    "Primeira tarefa",
                                                    null,
                                                    null,
                                                    Priority.HIGH,
                                                    "mando ate sexta"),
                                            ActionItem.fresh(
                                                    "Segunda tarefa",
                                                    null,
                                                    null,
                                                    Priority.MEDIUM,
                                                    "vamos fechar")),
                                    List.of(),
                                    List.of(),
                                    "stub-authz-1",
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

    /** Deliberately carries no authorization declaration — see the default-deny test above. */
    @RestController
    @RequestMapping("/__authz-probe")
    public static class UndeclaredAuthorizationProbeController {

        @GetMapping
        public Map<String, String> undeclared() {
            return Map.of("reached", "true");
        }
    }
}

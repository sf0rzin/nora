package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.embedding.EmbeddingService;
import br.com.nora.api.application.ports.EmbeddingClient;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.BuyingSignalType;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
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
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
 * The invariant ADR 0041 §2 exists to make testable: an MCP client can never see more than the user
 * it acts for can see in the web application. Proven over HTTP, against a real Postgres, on all
 * five tools of the first cut.
 *
 * <p>Two halves, because the invariant has two failure modes:
 *
 * <ul>
 *   <li><b>Tenant.</b> A principal of tenant A must get zero of tenant B's meetings, meeting
 *       detail, semantic search results, tasks and Customer Confidence. This is the failure the
 *       task's acceptance criteria name resource by resource, and it is the one row-level security
 *       would NOT catch here: RLS is off by default in this repository, so a dropped tenant filter
 *       passes green in development.
 *   <li><b>Policy.</b> Inside one tenant, a Deny over one specific meeting must hide exactly that
 *       meeting — both the unconditional form written against the meeting's own ARN and the
 *       conditional form written against one of its attributes. The second is the one that breaks
 *       silently: a per-item filter replaced by a single wildcard check still refuses nobody, and
 *       the wildcard evaluation has no attributes in its context, so a conditional Deny never
 *       matches and the whole tenant is released.
 * </ul>
 *
 * <p>Both intruders are fully entitled inside their own scope — the Root of tenant B and a non-Root
 * member holding {@code meeting:*} and {@code task:*} over B's own resources. A principal with no
 * policies would be refused before tenant scoping was ever consulted, so a test built on one would
 * pass with the tenant filter deleted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(McpIsolationIntegrationTest.StubConfig.class)
class McpIsolationIntegrationTest {

    private static final String PASSWORD = "SenhaForte123";
    private static final String TRANSCRIPT =
            "Ana: vamos fechar a renovacao do contrato.\nBruno: mando a proposta na sexta.";

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
    @Autowired EmbeddingService embeddings;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /* ===================== half one: cross-tenant ====================== */

    @Test
    void everyTool_returnsNothingOfAnotherTenant() throws Exception {
        Workspace alfa = workspace("mcp-iso-a@nora.dev", "Alfa");
        Workspace beta = workspace("mcp-iso-b@nora.dev", "Beta");

        // Positive control first: each workspace really does have something of its own to hide.
        assertThat(meetingIds(alfa.mcp())).containsExactly(alfa.meetingId().toString());
        assertThat(meetingIds(beta.mcp())).containsExactly(beta.meetingId().toString());

        // 1) list_meetings — B's client never sees A's meeting.
        assertThat(meetingIds(beta.mcp())).doesNotContain(alfa.meetingId().toString());

        // 2) get_meeting — A's id, presented by B's client, is not found rather than returned.
        JsonNode detail = call(beta.mcp(), "get_meeting", Map.of("meetingId", alfa.meetingId()));
        assertThat(detail.get("isError").asBoolean()).isTrue();

        // 3) search_meetings — the query matches both transcripts, and B gets only its own.
        JsonNode search = call(beta.mcp(), "search_meetings", Map.of("query", "renovacao"));
        List<String> hits = idsOf(structured(search), "meetings");
        assertThat(hits).containsExactly(beta.meetingId().toString());

        // 4) list_tasks — B sees its own two action items and none of A's.
        List<String> tasksOfAlfa = taskIds(alfa.mcp());
        List<String> tasksOfBeta = taskIds(beta.mcp());
        assertThat(tasksOfAlfa).hasSize(2);
        assertThat(tasksOfBeta).hasSize(2);
        assertThat(tasksOfBeta).doesNotContainAnyElementsOf(tasksOfAlfa);

        // 5) get_customer_confidence — A's assessment is unreachable through B's credential, and
        // B's own assessment names B's own account.
        JsonNode foreign =
                call(beta.mcp(), "get_customer_confidence", Map.of("meetingId", alfa.meetingId()));
        assertThat(foreign.get("isError").asBoolean()).isTrue();
        JsonNode own =
                call(beta.mcp(), "get_customer_confidence", Map.of("meetingId", beta.meetingId()));
        assertThat(structured(own).get("customerConfidence").get("accountName").asText())
                .isEqualTo(accountName(beta.tenantId()));
    }

    /**
     * The same five tools through a non-Root member of B holding everything it needs at home. The
     * Root bypass short-circuits the evaluator entirely, so without this the ordinary policy path
     * would never be exercised.
     */
    @Test
    void everyTool_isAlsoTenantScopedForANonRootMember() throws Exception {
        Workspace alfa = workspace("mcp-iso-m-a@nora.dev", "Alfa");
        Workspace beta = workspace("mcp-iso-m-b@nora.dev", "Beta");
        String member = memberWithFullReadRights(beta, "mcp-iso-m-b-member@nora.dev");

        assertThat(meetingIds(member)).containsExactly(beta.meetingId().toString());
        assertThat(taskIds(member)).containsExactlyInAnyOrderElementsOf(taskIds(beta.mcp()));

        JsonNode detail = call(member, "get_meeting", Map.of("meetingId", alfa.meetingId()));
        assertThat(detail.get("isError").asBoolean()).isTrue();

        JsonNode confidence =
                call(member, "get_customer_confidence", Map.of("meetingId", alfa.meetingId()));
        assertThat(confidence.get("isError").asBoolean()).isTrue();

        JsonNode search = call(member, "search_meetings", Map.of("query", "renovacao"));
        List<String> hits = idsOf(structured(search), "meetings");
        assertThat(hits).containsExactly(beta.meetingId().toString());
    }

    /* ======================= half two: policy Deny ====================== */

    @Test
    void unconditionalDenyOnOneMeeting_hidesItFromEveryReadingTool() throws Exception {
        Workspace ws = workspace("mcp-deny-root@nora.dev", "Deny");
        UUID secret = uploadAndIndex(ws, "Reuniao reservada", Map.of());

        UUID memberId = insertActiveMember(ws.tenantId(), "mcp-deny-member@nora.dev", "Member");
        String allow = statement("Allow", "meeting:*", arn(ws.tenantId(), ":meeting/*"));
        String deny = statement("Deny", "meeting:read", arn(ws.tenantId(), ":meeting/" + secret));
        attach(ws.jwt(), memberId, createPolicy(ws.jwt(), "DenyOne", document(allow, deny)));
        String member = mintFor("mcp-deny-member@nora.dev");

        // The denied meeting disappears; the other one is still there, which is what makes this a
        // test of the filter and not of a listing that broke for everyone.
        List<String> visible = meetingIds(member);
        assertThat(visible).contains(ws.meetingId().toString());
        assertThat(visible).doesNotContain(secret.toString());

        JsonNode detail = call(member, "get_meeting", Map.of("meetingId", secret));
        assertThat(detail.get("isError").asBoolean()).isTrue();

        JsonNode denied = call(member, "get_customer_confidence", Map.of("meetingId", secret));
        assertThat(denied.get("isError").asBoolean()).isTrue();

        // Positive control on the search path: the Root's own client does find it, so the member's
        // empty result is the policy filter and not a query that matches nothing.
        JsonNode rootHit = call(ws.mcp(), "search_meetings", Map.of("query", "reservada"));
        assertThat(idsOf(structured(rootHit), "meetings")).contains(secret.toString());

        JsonNode search = call(member, "search_meetings", Map.of("query", "reservada"));
        assertThat(idsOf(structured(search), "meetings")).doesNotContain(secret.toString());
    }

    @Test
    void conditionalDenyOnAMeetingAttribute_hidesItFromEveryReadingTool() throws Exception {
        Workspace ws = workspace("mcp-cond-root@nora.dev", "Cond");
        UUID confidential =
                uploadAndIndex(ws, "Reuniao do juridico", Map.of("department", "Juridico"));

        UUID memberId = insertActiveMember(ws.tenantId(), "mcp-cond-member@nora.dev", "Member");
        String allow = statement("Allow", "meeting:*", arn(ws.tenantId(), ":meeting/*"));
        String deny =
                conditionalStatement(
                        "Deny",
                        "meeting:read",
                        arn(ws.tenantId(), ":meeting/*"),
                        "{\"StringEquals\":{\"department\":\"Juridico\"}}");
        attach(ws.jwt(), memberId, createPolicy(ws.jwt(), "DenyLegal", document(allow, deny)));
        String member = mintFor("mcp-cond-member@nora.dev");

        List<String> visible = meetingIds(member);
        assertThat(visible).contains(ws.meetingId().toString());
        assertThat(visible).doesNotContain(confidential.toString());

        JsonNode detail = call(member, "get_meeting", Map.of("meetingId", confidential));
        assertThat(detail.get("isError").asBoolean()).isTrue();

        JsonNode rootHit = call(ws.mcp(), "search_meetings", Map.of("query", "juridico"));
        assertThat(idsOf(structured(rootHit), "meetings")).contains(confidential.toString());

        JsonNode search = call(member, "search_meetings", Map.of("query", "juridico"));
        assertThat(idsOf(structured(search), "meetings")).doesNotContain(confidential.toString());
    }

    /* ============================== fixtures ============================ */

    /** A tenant with its Root JWT, an MCP credential, and one analysed, indexed meeting. */
    private record Workspace(String jwt, String mcp, UUID tenantId, UUID meetingId) {}

    private Workspace workspace(String email, String name) throws Exception {
        String jwt = signupAndLogin(email, name);
        UUID tenantId = readClaim(jwt, "tenantId");
        String mcp = mintToken(jwt, "client-" + name);
        Workspace partial = new Workspace(jwt, mcp, tenantId, null);
        UUID meetingId = uploadAndIndex(partial, "Renovacao " + name, Map.of());
        return new Workspace(jwt, mcp, tenantId, meetingId);
    }

    /** Uploads, analyses (which extracts the tasks and the confidence) and indexes for search. */
    private UUID uploadAndIndex(Workspace ws, String title, Map<String, String> attributes)
            throws Exception {
        UUID meetingId = UUID.fromString(uploadMeeting(ws.jwt(), title, attributes));
        analysisService.run(meetingId, ws.tenantId());
        embeddings.index(meetingId, ws.tenantId(), title + " renovacao do contrato e proposta");
        return meetingId;
    }

    /** A non-Root member holding read rights over its OWN tenant's meetings and tasks. */
    private String memberWithFullReadRights(Workspace ws, String email) throws Exception {
        UUID memberId = insertActiveMember(ws.tenantId(), email, "Read Member");
        String meetings = statement("Allow", "meeting:*", arn(ws.tenantId(), ":meeting/*"));
        String tasks = statement("Allow", "task:*", arn(ws.tenantId(), ":task/*"));
        attach(ws.jwt(), memberId, createPolicy(ws.jwt(), "AllowReads", document(meetings, tasks)));
        return mintFor(email);
    }

    /** Logs the member in and mints an MCP credential with the session it just obtained. */
    private String mintFor(String email) throws Exception {
        return mintToken(login(email), "client-" + email);
    }

    /* ============================== MCP calls =========================== */

    private List<String> meetingIds(String mcpToken) throws Exception {
        return idsOf(structured(call(mcpToken, "list_meetings", Map.of())), "meetings");
    }

    private List<String> taskIds(String mcpToken) throws Exception {
        return idsOf(structured(call(mcpToken, "list_tasks", Map.of())), "tasks");
    }

    private static List<String> idsOf(JsonNode payload, String field) {
        List<String> ids = new ArrayList<>();
        payload.get(field).forEach(item -> ids.add(item.get("id").asText()));
        return ids;
    }

    private static JsonNode structured(JsonNode toolResult) {
        assertThat(toolResult.get("isError").asBoolean()).as("%s", toolResult).isFalse();
        return toolResult.get("structuredContent");
    }

    /** One {@code tools/call}, returning the tool result (which may carry {@code isError}). */
    private JsonNode call(String mcpToken, String tool, Map<String, ?> arguments) throws Exception {
        Map<String, Object> params = Map.of("name", tool, "arguments", stringify(arguments));
        JsonNode envelope = rpc(mcpToken, "tools/call", params);
        assertThat(envelope.has("result")).as("envelope=%s", envelope).isTrue();
        return envelope.get("result");
    }

    /** UUIDs are sent as the strings an MCP client would actually put on the wire. */
    private static Map<String, Object> stringify(Map<String, ?> arguments) {
        Map<String, Object> out = new LinkedHashMap<>();
        arguments.forEach((k, v) -> out.put(k, v instanceof UUID id ? id.toString() : v));
        return out;
    }

    private JsonNode rpc(String mcpToken, String method, Map<String, ?> params) throws Exception {
        String body = json(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(mcpToken);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> resp = rest.exchange("/mcp", HttpMethod.POST, entity, String.class);
        return read(resp, HttpStatus.OK);
    }

    private String mintToken(String jwt, String name) throws Exception {
        String body = json(Map.of("name", name));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/mcp/tokens", body, jwt);
        return read(resp, HttpStatus.CREATED).get("token").asText();
    }

    /* ============================== IAM helpers ========================= */

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

    private static String conditionalStatement(
            String effect, String action, String resource, String condition) {
        String head = statement(effect, action, resource);
        return head.substring(0, head.length() - 1) + ",\"condition\":" + condition + "}";
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

    /* =============================== helpers ============================ */

    private static String accountName(UUID tenantId) {
        return "Conta " + tenantId.toString().substring(0, 8);
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

    private String uploadMeeting(String token, String title, Map<String, String> attributes)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaHeaders = new HttpHeaders();
        metaHeaders.setContentType(MediaType.APPLICATION_JSON);
        String metadata =
                json(
                        Map.of(
                                "title", title,
                                "transcriptFormat", "TXT",
                                "attributes", attributes));
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

    /* ========================= test-scoped beans ======================== */

    @TestConfiguration
    static class StubConfig {

        /**
         * Deterministic worker: two action items and one Customer Confidence block per analysis,
         * with the account name derived from the tenant so a leak across tenants is visible in the
         * assertion rather than merely absent.
         */
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
                                    "Resumo stub para o teste de isolamento do MCP.",
                                    Sentiment.NEUTRAL,
                                    List.of("renovacao"),
                                    List.of(),
                                    List.of(
                                            ActionItem.fresh(
                                                    "Enviar proposta",
                                                    null,
                                                    null,
                                                    Priority.HIGH,
                                                    "mando a proposta na sexta"),
                                            ActionItem.fresh(
                                                    "Revisar contrato",
                                                    null,
                                                    null,
                                                    Priority.MEDIUM,
                                                    "vamos fechar a renovacao do contrato")),
                                    List.of(),
                                    List.of(),
                                    "stub-mcp-iso-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    0);
                    BuyingSignal signal =
                            new BuyingSignal(
                                    BuyingSignalType.BUDGET_DISCUSSED,
                                    "temos orcamento aprovado para a renovacao",
                                    0.8,
                                    0);
                    CustomerConfidenceCarrier carrier =
                            new CustomerConfidenceCarrier(
                                    "Conta " + tenantId.toString().substring(0, 8),
                                    72,
                                    ConfidenceBand.MEDIUM,
                                    ConfidenceTrend.STABLE,
                                    List.of(signal),
                                    List.of(),
                                    "rationale do stub para o teste de isolamento do MCP");
                    return AnalysisResult.of(analysis, null, carrier);
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

        /** Bag-of-words embedding: word overlap becomes cosine similarity, deterministically. */
        @Bean
        @Primary
        EmbeddingClient stubEmbeddingClient() {
            return new EmbeddingClient() {
                @Override
                public float[] embed(String text) {
                    float[] v = new float[64];
                    for (String w : text.toLowerCase().split("\\W+")) {
                        if (!w.isBlank()) {
                            v[Math.floorMod(w.hashCode(), 64)] += 1f;
                        }
                    }
                    return v;
                }

                @Override
                public String modelId() {
                    return "stub:test";
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }
            };
        }
    }
}

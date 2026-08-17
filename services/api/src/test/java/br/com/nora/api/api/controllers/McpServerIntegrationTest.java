package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The transport and the credential of ADR 0041: the handshake, the pinned protocol version, and the
 * boundary that keeps an MCP token from becoming a second session credential for the REST API.
 *
 * <p>The authorization invariant is proven separately, in {@code McpIsolationIntegrationTest}. What
 * this file asserts is everything around it — that an incompatible client is refused loudly rather
 * than served a shape it cannot read, that the catalogue is constant text, and that the two
 * credentials of this system do not substitute for one another in either direction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class McpServerIntegrationTest {

    private static final String PASSWORD = "SenhaForte123";
    private static final String PINNED_VERSION = "2025-11-25";

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

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /* =============================== handshake ================================ */

    @Test
    void initialize_answersWithTheNegotiatedVersionAndTheToolsCapability() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-init@nora.dev", "Init"), "desktop");

        JsonNode result = rpcResult(mcp, "initialize", initParams(PINNED_VERSION));

        assertThat(result.get("protocolVersion").asText()).isEqualTo(PINNED_VERSION);
        assertThat(result.get("capabilities").has("tools")).isTrue();
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("nora");
    }

    /**
     * The pin, doing its job. An older revision is not silently accepted and then served the newer
     * shapes: the client gets the specification's own error, naming what it could use instead.
     */
    @Test
    void initialize_withAnUnimplementedVersion_failsLoudlyNamingWhatIsSupported() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-oldver@nora.dev", "Old"), "desktop");

        JsonNode envelope = rpc(mcp, "initialize", initParams("2024-11-05"), HttpStatus.OK);

        JsonNode error = envelope.get("error");
        assertThat(error.get("code").asInt()).isEqualTo(-32602);
        assertThat(error.get("data").get("requested").asText()).isEqualTo("2024-11-05");
        assertThat(versions(error.get("data").get("supported"))).contains(PINNED_VERSION);
    }

    /**
     * The transport requires a 400 on a protocol-version header the server does not implement, and
     * a dual-era client reads exactly that 400 as "this server still speaks the handshake".
     */
    @Test
    void anUnimplementedProtocolVersionHeader_isRejectedByTheTransport() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-hdr@nora.dev", "Header"), "desktop");

        HttpHeaders headers = mcpHeaders(mcp);
        headers.set("MCP-Protocol-Version", "1900-01-01");
        String body = json(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        ResponseEntity<String> resp = post("/mcp", body, headers);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode data = mapper.readTree(resp.getBody()).get("error").get("data");
        assertThat(versions(data.get("supported"))).contains(PINNED_VERSION);
    }

    /* ================================= tools ================================== */

    @Test
    void toolsList_carriesTheFiveReadOnlyToolsOfTheFirstCut() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-tools@nora.dev", "Tools"), "desktop");

        JsonNode result = rpcResult(mcp, "tools/list", null);

        List<String> names = new ArrayList<>();
        result.get("tools").forEach(tool -> names.add(tool.get("name").asText()));
        assertThat(names)
                .containsExactlyInAnyOrder(
                        "list_meetings",
                        "get_meeting",
                        "search_meetings",
                        "list_tasks",
                        "get_customer_confidence");
    }

    /**
     * Tool descriptions land verbatim in an external model's context, so they must be the same
     * constant text for every caller of every tenant. Two workspaces, byte-identical catalogue.
     */
    @Test
    void toolsList_isIdenticalForTwoDifferentTenants() throws Exception {
        String one = mintToken(signupAndLogin("mcp-cat-a@nora.dev", "CatA"), "desktop");
        String two = mintToken(signupAndLogin("mcp-cat-b@nora.dev", "CatB"), "desktop");

        String first = rpcResult(one, "tools/list", null).toString();
        String second = rpcResult(two, "tools/list", null).toString();
        assertThat(first).isEqualTo(second);
    }

    /**
     * A tool call that actually runs. Every other test here stops at the transport, and {@code
     * tools/call} is the only method that reads the principal back out of the SecurityContext —
     * which is exactly where a filter downstream of the MCP chain once cleared it, letting a
     * request that had already passed {@code anyRequest().authenticated()} reach the handler
     * unauthenticated. The workspace is empty on purpose: the assertion is that the call is
     * authorized and answers, not what it finds.
     */
    @Test
    void toolsCall_runsAsTheUserThatMintedTheToken() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-call@nora.dev", "Call"), "desktop");

        Map<String, Object> params = Map.of("name", "list_meetings", "arguments", Map.of());
        JsonNode result = rpcResult(mcp, "tools/call", params);

        assertThat(result.get("isError").asBoolean()).as("%s", result).isFalse();
        assertThat(result.get("structuredContent").get("meetings")).isEmpty();
        assertThat(result.get("content").get(0).get("type").asText()).isEqualTo("text");
    }

    @Test
    void unknownMethod_isAJsonRpcErrorRatherThanASilentEmptyResult() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-unknown@nora.dev", "Unknown"), "desktop");

        JsonNode envelope = rpc(mcp, "resources/list", null, HttpStatus.OK);

        assertThat(envelope.get("error").get("code").asInt()).isEqualTo(-32601);
    }

    /** A message with no {@code id} is a notification: accepted, acknowledged, no body. */
    @Test
    void notification_isAcceptedWithNoBody() throws Exception {
        String mcp = mintToken(signupAndLogin("mcp-notify@nora.dev", "Notify"), "desktop");

        String body = json(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        ResponseEntity<String> resp = post("/mcp", body, mcpHeaders(mcp));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    /* ============================== the credential ============================= */

    @Test
    void theEndpointRefusesEveryRequestWithoutALiveCredential() throws Exception {
        String jwt = signupAndLogin("mcp-cred@nora.dev", "Cred");
        String body = json(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));

        // No credential at all.
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);
        assertThat(postStatus(body, anonymous)).isEqualTo(HttpStatus.UNAUTHORIZED);

        // A perfectly valid session JWT is still not an MCP credential. The two do not substitute
        // for one another, in either direction — see the REST half below.
        assertThat(postStatus(body, mcpHeaders(jwt))).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Something shaped like an MCP token but never minted.
        String forged = "nora_mcp_" + "x".repeat(43);
        assertThat(postStatus(body, mcpHeaders(forged))).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The boundary that makes ADR 0041 §4's read-only first cut a property of the CREDENTIAL and
     * not merely of which tools happen to exist. An MCP token reaches the MCP endpoint and nothing
     * else, so a leaked one cannot upload a meeting or patch a task over REST.
     */
    @Test
    void anMcpToken_authenticatesNothingOutsideTheMcpEndpoint() throws Exception {
        String jwt = signupAndLogin("mcp-scope@nora.dev", "Scope");
        String mcp = mintToken(jwt, "desktop");

        assertThat(getStatus("/meetings", mcp)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getStatus("/tasks", mcp)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(getStatus("/auth/me", mcp)).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Not even its own management surface: a token cannot mint a successor for itself.
        assertThat(getStatus("/mcp/tokens", mcp)).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Positive control on the very same routes with the session that minted it.
        assertThat(getStatus("/meetings", jwt)).isEqualTo(HttpStatus.OK);
        assertThat(getStatus("/mcp/tokens", jwt)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void revokingATokenStopsItImmediately() throws Exception {
        String jwt = signupAndLogin("mcp-revoke@nora.dev", "Revoke");
        JsonNode created = mint(jwt, "desktop");
        String mcp = created.get("token").asText();

        assertThat(rpcResult(mcp, "tools/list", null).has("tools")).isTrue();

        String path = "/mcp/tokens/" + created.get("id").asText();
        ResponseEntity<String> deleted =
                rest.exchange(path, HttpMethod.DELETE, jwtEntity(jwt), String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String body = json(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"));
        assertThat(postStatus(body, mcpHeaders(mcp))).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Revocation is idempotent, so a retried request cannot fail for having succeeded.
        ResponseEntity<String> again =
                rest.exchange(path, HttpMethod.DELETE, jwtEntity(jwt), String.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** The plaintext exists in exactly one response and is never recoverable afterwards. */
    @Test
    void thePlaintextIsReturnedOnceAndNeverAppearsInTheListing() throws Exception {
        String jwt = signupAndLogin("mcp-once@nora.dev", "Once");
        JsonNode created = mint(jwt, "desktop");

        assertThat(created.get("token").asText()).startsWith("nora_mcp_");

        ResponseEntity<String> listing =
                rest.exchange("/mcp/tokens", HttpMethod.GET, jwtEntity(jwt), String.class);
        JsonNode items = read(listing, HttpStatus.OK).get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).has("token")).isFalse();
        assertThat(items.get(0).get("active").asBoolean()).isTrue();
        assertThat(listing.getBody()).doesNotContain(created.get("token").asText());
    }

    /** One user's tokens are invisible to another, which is what makes revocation meaningful. */
    @Test
    void aTokenOfAnotherUserCannotBeListedOrRevoked() throws Exception {
        String owner = signupAndLogin("mcp-owner@nora.dev", "Owner");
        String stranger = signupAndLogin("mcp-stranger@nora.dev", "Stranger");
        JsonNode created = mint(owner, "desktop");

        ResponseEntity<String> listing =
                rest.exchange("/mcp/tokens", HttpMethod.GET, jwtEntity(stranger), String.class);
        assertThat(read(listing, HttpStatus.OK).get("items")).isEmpty();

        String path = "/mcp/tokens/" + created.get("id").asText();
        ResponseEntity<String> revoke =
                rest.exchange(path, HttpMethod.DELETE, jwtEntity(stranger), String.class);
        assertThat(revoke.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // Untouched: the owner's token still works.
        assertThat(rpcResult(created.get("token").asText(), "tools/list", null).has("tools"))
                .isTrue();
    }

    /* ================================ helpers ================================= */

    private static Map<String, Object> initParams(String version) {
        return Map.of(
                "protocolVersion",
                version,
                "capabilities",
                Map.of(),
                "clientInfo",
                Map.of("name", "test-client", "version", "1.0.0"));
    }

    private static List<String> versions(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }

    private JsonNode rpcResult(String mcpToken, String method, Map<String, ?> params)
            throws Exception {
        JsonNode envelope = rpc(mcpToken, method, params, HttpStatus.OK);
        assertThat(envelope.has("result")).as("envelope=%s", envelope).isTrue();
        return envelope.get("result");
    }

    private JsonNode rpc(String mcpToken, String method, Map<String, ?> params, HttpStatus expected)
            throws Exception {
        Map<String, Object> message = new java.util.LinkedHashMap<>();
        message.put("jsonrpc", "2.0");
        message.put("id", 1);
        message.put("method", method);
        if (params != null) {
            message.put("params", params);
        }
        return read(post("/mcp", json(message), mcpHeaders(mcpToken)), expected);
    }

    private ResponseEntity<String> post(String path, String body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpStatusCode postStatus(String body, HttpHeaders headers) {
        return post("/mcp", body, headers).getStatusCode();
    }

    private static HttpHeaders mcpHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private static HttpEntity<Void> jwtEntity(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt);
        return new HttpEntity<>(headers);
    }

    private HttpStatusCode getStatus(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, jwtEntity(token), String.class).getStatusCode();
    }

    private String mintToken(String jwt, String name) throws Exception {
        return mint(jwt, name).get("token").asText();
    }

    private JsonNode mint(String jwt, String name) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt);
        String body = json(Map.of("name", name));
        return read(post("/mcp/tokens", body, headers), HttpStatus.CREATED);
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private String signupAndLogin(String email, String name) throws Exception {
        String payload = json(Map.of("email", email, "password", PASSWORD, "displayName", name));
        JsonNode signup = postJson("/auth/signup", payload, HttpStatus.CREATED);
        String verify = json(Map.of("token", signup.get("emailVerificationDevToken").asText()));
        postJson("/auth/verify-email", verify, HttpStatus.NO_CONTENT);
        String login = json(Map.of("email", email, "password", PASSWORD));
        return postJson("/auth/login", login, HttpStatus.OK).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String body, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return read(post(path, body, headers), expected);
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

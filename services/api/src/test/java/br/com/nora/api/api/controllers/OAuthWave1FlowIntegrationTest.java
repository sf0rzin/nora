package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.integration.OAuthProviderConfig;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.GenericOAuthClient;
import br.com.nora.api.infrastructure.integration.GitHubClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
 * End-to-end flow of the wave 1 generic OAuth providers (GitHub as the representative + Notion in
 * authorize): start → generic callback (signed state) → persisted connection → Flows'
 * github_create_issue action using the token → disconnect. The token exchange and GitHub are
 * stubbed; the rest of the path (generic controller, service, RLS, token encryption, migration
 * V025) is real against Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class OAuthWave1FlowIntegrationTest {

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
        registry.add("nora.integrations.github.client-id", () -> "github-client-id-teste");
        registry.add("nora.integrations.github.client-secret", () -> "github-secret-teste");
        registry.add(
                "nora.integrations.github.redirect-uri",
                () -> "http://localhost:8080/integrations/github/oauth/callback");
        registry.add("nora.integrations.notion.client-id", () -> "notion-client-id-teste");
        registry.add("nora.integrations.notion.client-secret", () -> "notion-secret-teste");
        registry.add(
                "nora.integrations.notion.redirect-uri",
                () -> "http://localhost:8080/integrations/notion/oauth/callback");
        registry.add("nora.integrations.state-secret", () -> "segredo-state-teste");
    }

    static final RecordingGitHub GITHUB = new RecordingGitHub();

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        GITHUB.issues.clear();
    }

    @Test
    void fluxoCompleto_startCallbackStatusAcaoDisconnect() throws Exception {
        String token = signupAndLogin("wave1-full@nora.dev", "SenhaForte123", "Wave1 Full");

        // 1) Initial status: github/notion configured (props above), not connected;
        //    todoist/linear without credentials = not configured.
        JsonNode before = authGet("/integrations", token).read(HttpStatus.OK);
        assertThat(providerNode(before, "github").get("configured").asBoolean()).isTrue();
        assertThat(providerNode(before, "github").get("connected").asBoolean()).isFalse();
        assertThat(providerNode(before, "notion").get("configured").asBoolean()).isTrue();
        assertThat(providerNode(before, "todoist").get("configured").asBoolean()).isFalse();
        assertThat(providerNode(before, "linear").get("configured").asBoolean()).isFalse();

        // 2) Start GitHub: authorization URL with scope repo and signed state.
        JsonNode start =
                postJson("/integrations/github/oauth/start", Map.of(), token).read(HttpStatus.OK);
        String authorizeUrl = start.get("authorizeUrl").asText();
        assertThat(authorizeUrl).startsWith("https://github.com/login/oauth/authorize?");
        assertThat(authorizeUrl).contains("client_id=github-client-id-teste");
        assertThat(authorizeUrl).contains("scope=repo");
        String state = queryParam(authorizeUrl, "state");
        assertThat(state).isNotBlank();

        // 2b) Start Notion: owner=user and no scope (app capabilities).
        JsonNode notionStart =
                postJson("/integrations/notion/oauth/start", Map.of(), token).read(HttpStatus.OK);
        assertThat(notionStart.get("authorizeUrl").asText())
                .startsWith("https://api.notion.com/v1/oauth/authorize?")
                .contains("owner=user")
                .doesNotContain("scope=");

        // 2c) Start on an unconfigured provider fails visibly (422).
        assertThat(
                        postJson("/integrations/todoist/oauth/start", Map.of(), token)
                                .response
                                .getStatusCode()
                                .value())
                .isEqualTo(422);

        // 3) Generic callback (public) → 302 to the front end with success.
        ResponseEntity<String> callback =
                rest.exchange(
                        callbackUri("github", state, "code-gh"),
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation())
                .hasToString("http://localhost:3000/integracoes?connected=github");

        // 4) Status: connected.
        JsonNode after = authGet("/integrations", token).read(HttpStatus.OK);
        assertThat(providerNode(after, "github").get("connected").asBoolean()).isTrue();

        // 5) github_create_issue action in a workflow: POST /test (sample data).
        String definition =
                """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                     "position":{"x":0,"y":0}},
                    {"id":"a1","kind":"action","type":"github_create_issue",
                     "params":{"repo":"stratfy/nora"},"position":{"x":280,"y":0}}
                  ],
                  "edges": [{"id":"e1","source":"t1","target":"a1"}]
                }
                """;
        JsonNode wf =
                postJson(
                                "/workflows",
                                Map.of(
                                        "name",
                                        "Issues via GitHub",
                                        "definition",
                                        mapper.readTree(definition)),
                                token)
                        .read(HttpStatus.CREATED);
        JsonNode execution =
                postJson("/workflows/" + wf.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("log").toString()).contains("no GitHub em stratfy/nora");
        assertThat(GITHUB.issues).isNotEmpty();
        assertThat(GITHUB.issues.get(0).accessToken()).isEqualTo("gho-token-1");
        assertThat(GITHUB.issues.get(0).repo()).isEqualTo("stratfy/nora");
        assertThat(GITHUB.issues.get(0).labels()).containsExactly("nora");

        // 6) Disconnect → status goes back to not connected and the action starts failing clearly.
        ResponseEntity<String> disconnect =
                rest.exchange(
                        "/integrations/github",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(token)),
                        String.class);
        assertThat(disconnect.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode disconnected = authGet("/integrations", token).read(HttpStatus.OK);
        assertThat(providerNode(disconnected, "github").get("connected").asBoolean()).isFalse();

        JsonNode failedExecution =
                postJson("/workflows/" + wf.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(failedExecution.get("status").asText()).isEqualTo("FAILED");
        assertThat(failedExecution.get("log").toString()).contains("não está conectada");
    }

    @Test
    void callback_stateInvalido_redirecionaComErro() {
        ResponseEntity<String> callback =
                rest.getForEntity(
                        "/integrations/github/oauth/callback?code=abc&state=forjado", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=integration_invalid_state");
    }

    @Test
    void callback_provedorDesconhecido_redirecionaComErro() {
        ResponseEntity<String> callback =
                rest.getForEntity(
                        "/integrations/qualquer/oauth/callback?code=abc&state=x", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=integration_unknown_provider");
    }

    /* ---------------- helpers ---------------- */

    private URI callbackUri(String provider, String state, String code) {
        return URI.create(
                rest.getRootUri()
                        + "/integrations/"
                        + provider
                        + "/oauth/callback?code="
                        + code
                        + "&state="
                        + java.net.URLEncoder.encode(state, StandardCharsets.UTF_8));
    }

    private JsonNode providerNode(JsonNode statusList, String provider) {
        for (JsonNode node : statusList) {
            if (provider.equals(node.get("provider").asText())) {
                return node;
            }
        }
        throw new AssertionError("provider não listado: " + provider);
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name),
                                null)
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken), null)
                .read(HttpStatus.NO_CONTENT);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", pwd), null)
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private RequestExec postJson(String path, Map<String, ?> body, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(
                rest.exchange(
                        path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class));
    }

    private final class RequestExec {
        final ResponseEntity<String> response;

        RequestExec(ResponseEntity<String> r) {
            this.response = r;
        }

        JsonNode read(HttpStatus expected) throws Exception {
            assertThat(response.getStatusCode())
                    .as("body=%s", response.getBody())
                    .isEqualTo(expected);
            return response.getBody() == null
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.getBody());
        }
    }

    /** Captures issue creations in memory (replaces the real HTTP calls to GitHub). */
    static class RecordingGitHub extends GitHubClient {
        record Issue(
                String accessToken, String repo, String title, String body, List<String> labels) {}

        final List<Issue> issues = new CopyOnWriteArrayList<>();

        RecordingGitHub() {
            super(new ObjectMapper());
        }

        @Override
        public String createIssue(
                String accessToken, String repo, String title, String body, List<String> labels) {
            issues.add(new Issue(accessToken, repo, title, body, labels));
            return "https://github.com/" + repo + "/issues/" + issues.size();
        }
    }

    @TestConfiguration
    static class TestBeans {

        /** Generic token exchange stubbed out (no network). */
        @Bean
        @Primary
        GenericOAuthClient stubGenericOAuth() {
            return new GenericOAuthClient() {
                @Override
                public TokenResponse exchangeCode(OAuthProviderConfig config, String code) {
                    return new TokenResponse("gho-token-1", null, "repo", null, null);
                }

                @Override
                public TokenResponse refresh(OAuthProviderConfig config, String refreshToken) {
                    throw new UnsupportedOperationException("nenhum provedor deste IT usa refresh");
                }
            };
        }

        @Bean
        @Primary
        GitHubClient recordingGitHub() {
            return GITHUB;
        }

        /** Resend e-mails silenced (no network) — this IT does not exercise them. */
        @Bean
        @Primary
        EmailSender silentEmailSender() {
            return new EmailSender() {
                @Override
                public void sendEmailVerification(String to, String name, String link) {}

                @Override
                public void sendPasswordReset(String to, String name, String link) {}

                @Override
                public void sendSignupAttemptOnExistingAccount(
                        String to, String name, String signInUrl) {}

                @Override
                public void sendInvitation(
                        String to, String tenant, String invitedBy, String url, int days) {}

                @Override
                public void sendWorkflowNotification(String to, String subject, String html) {}
            };
        }
    }
}

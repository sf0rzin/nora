package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.SlackOAuthClient;
import br.com.nora.api.infrastructure.integration.SlackClient;
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
 * Fluxo end-to-end da integração Slack (Fase 2 do GOAL): start (OAuth v2) → callback (state
 * assinado) → conexão persistida (bot token sem expiração, team name como conta) → ação
 * slack_post_message do Flows usando o token → disconnect → falha clara. Slack é stubado nos
 * ports/client; o resto do caminho (controller, service, RLS, cifra de token) é real contra
 * Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SlackFlowIntegrationTest {

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
        registry.add("nora.integrations.slack.client-id", () -> "slack-client-id-teste");
        registry.add("nora.integrations.slack.client-secret", () -> "slack-client-secret-teste");
        registry.add(
                "nora.integrations.slack.redirect-uri",
                () -> "http://localhost:8080/integrations/slack/oauth/callback");
        registry.add("nora.integrations.state-secret", () -> "segredo-state-teste");
    }

    static final RecordingSlack SLACK = new RecordingSlack();

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        SLACK.posted.clear();
    }

    @Test
    void fluxoCompleto_startCallbackStatusAcaoDisconnect() throws Exception {
        String token = signupAndLogin("slack-full@nora.dev", "SenhaForte123", "Slack Full");

        // 1) Status inicial: slack configurado, não conectado.
        JsonNode before = authGet("/integrations", token).read(HttpStatus.OK);
        JsonNode slackBefore = providerNode(before, "slack");
        assertThat(slackBefore.get("configured").asBoolean()).isTrue();
        assertThat(slackBefore.get("connected").asBoolean()).isFalse();

        // 2) Start: URL de autorização OAuth v2 com state assinado.
        JsonNode start =
                postJson("/integrations/slack/oauth/start", Map.of(), token).read(HttpStatus.OK);
        String authorizeUrl = start.get("authorizeUrl").asText();
        assertThat(authorizeUrl).startsWith("https://slack.com/oauth/v2/authorize?");
        assertThat(authorizeUrl).contains("client_id=slack-client-id-teste");
        assertThat(authorizeUrl).contains("scope=chat%3Awrite%2Cchannels%3Aread");
        String state = queryParam(authorizeUrl, "state");
        assertThat(state).isNotBlank();

        // 3) Callback (público, como o redirect do Slack faria) → 302 pro front com sucesso.
        ResponseEntity<String> callback =
                rest.exchange(
                        callbackUri(state, "code-slack"),
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation())
                .hasToString("http://localhost:3000/integracoes?connected=slack");

        // 4) Status: conectado com a workspace identificada.
        JsonNode after = authGet("/integrations", token).read(HttpStatus.OK);
        JsonNode slackAfter = providerNode(after, "slack");
        assertThat(slackAfter.get("connected").asBoolean()).isTrue();
        assertThat(slackAfter.get("externalAccount").asText()).isEqualTo("Time NORA");

        // 5) Ação slack_post_message num workflow: POST /test (dados de exemplo, sem reunião).
        String definition =
                """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                     "position":{"x":0,"y":0}},
                    {"id":"a1","kind":"action","type":"slack_post_message",
                     "params":{"channel":"#vendas"},"position":{"x":280,"y":0}}
                  ],
                  "edges": [{"id":"e1","source":"t1","target":"a1"}]
                }
                """;
        JsonNode wf =
                postJson(
                                "/workflows",
                                Map.of(
                                        "name",
                                        "Postar no Slack",
                                        "definition",
                                        mapper.readTree(definition)),
                                token)
                        .read(HttpStatus.CREATED);
        JsonNode execution =
                postJson("/workflows/" + wf.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("log").toString())
                .contains("Mensagem enviada no Slack para #vendas");
        assertThat(SLACK.posted).hasSize(1);
        assertThat(SLACK.posted.get(0).botToken()).isEqualTo("xoxb-token-teste");
        assertThat(SLACK.posted.get(0).channel()).isEqualTo("#vendas");
        assertThat(SLACK.posted.get(0).text()).contains("analisada pelo NORA");

        // 6) Disconnect → status volta a não conectado e a ação passa a falhar com clareza.
        ResponseEntity<String> disconnect =
                rest.exchange(
                        "/integrations/slack",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(token)),
                        String.class);
        assertThat(disconnect.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode disconnected = authGet("/integrations", token).read(HttpStatus.OK);
        assertThat(providerNode(disconnected, "slack").get("connected").asBoolean()).isFalse();

        JsonNode failedExecution =
                postJson("/workflows/" + wf.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(failedExecution.get("status").asText()).isEqualTo("FAILED");
        assertThat(failedExecution.get("log").toString()).contains("não está conectada");
    }

    @Test
    void callback_stateForjado_redirecionaComErro() {
        ResponseEntity<String> callback =
                rest.getForEntity(
                        "/integrations/slack/oauth/callback?code=abc&state=forjado", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=integration_invalid_state");
    }

    @Test
    void callback_usuarioNegouConsentimento_redirecionaComErro() {
        ResponseEntity<String> callback =
                rest.getForEntity(
                        "/integrations/slack/oauth/callback?error=access_denied", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=access_denied");
    }

    @Test
    void conexaoEhIsoladaPorTenant() throws Exception {
        String tokenA = signupAndLogin("slack-a@nora.dev", "SenhaForte123", "A");
        String tokenB = signupAndLogin("slack-b@nora.dev", "SenhaForte123", "B");

        // A conecta.
        JsonNode start =
                postJson("/integrations/slack/oauth/start", Map.of(), tokenA).read(HttpStatus.OK);
        String state = queryParam(start.get("authorizeUrl").asText(), "state");
        rest.exchange(callbackUri(state, "x"), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        // B não vê a conexão de A.
        JsonNode statusB = authGet("/integrations", tokenB).read(HttpStatus.OK);
        assertThat(providerNode(statusB, "slack").get("connected").asBoolean()).isFalse();
    }

    /* ---------------- helpers ---------------- */

    private URI callbackUri(String state, String code) {
        return URI.create(
                rest.getRootUri()
                        + "/integrations/slack/oauth/callback?code="
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

    /** Captura posts no Slack em memória (substitui as chamadas HTTP reais). */
    static class RecordingSlack extends SlackClient {
        record Posted(String botToken, String channel, String text) {}

        final List<Posted> posted = new CopyOnWriteArrayList<>();

        RecordingSlack() {
            super(new ObjectMapper());
        }

        @Override
        public String postMessage(String botToken, String channel, String text) {
            posted.add(new Posted(botToken, channel, text));
            return "1718000000.000100";
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        SlackOAuthClient stubSlackOAuth() {
            return (code, redirectUri) ->
                    new SlackOAuthClient.TokenResponse(
                            "xoxb-token-teste", "Time NORA", "chat:write,channels:read");
        }

        @Bean
        @Primary
        SlackClient recordingSlack() {
            return SLACK;
        }

        /** E-mails Resend silenciados (sem rede) — este IT não os exercita. */
        @Bean
        @Primary
        EmailSender silentEmailSender() {
            return new EmailSender() {
                @Override
                public void sendEmailVerification(String to, String name, String link) {}

                @Override
                public void sendPasswordReset(String to, String name, String link) {}

                @Override
                public void sendInvitation(
                        String to, String tenant, String invitedBy, String url, int days) {}

                @Override
                public void sendWorkflowNotification(String to, String subject, String html) {}
            };
        }
    }
}

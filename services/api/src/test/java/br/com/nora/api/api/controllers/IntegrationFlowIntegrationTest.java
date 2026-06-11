package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import br.com.nora.api.infrastructure.integration.GoogleWorkspaceClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
 * Fluxo end-to-end do hub de integrações OAuth (Fase 2 do GOAL): start → callback (state assinado)
 * → conexão persistida → ação gmail_send_email do Flows usando o token → disconnect. Google é
 * stubado nos ports; o resto do caminho (controller, service, RLS, cifra de token via adapter) é
 * real contra Postgres.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IntegrationFlowIntegrationTest {

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
        registry.add("nora.integrations.google.client-id", () -> "client-id-teste");
        registry.add("nora.integrations.google.client-secret", () -> "client-secret-teste");
        registry.add(
                "nora.integrations.google.redirect-uri",
                () -> "http://localhost:8080/integrations/google/oauth/callback");
        registry.add("nora.integrations.state-secret", () -> "segredo-state-teste");
    }

    static final RecordingGmail GMAIL = new RecordingGmail();

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        GMAIL.sent.clear();
    }

    @Test
    void fluxoCompleto_startCallbackStatusAcaoDisconnect() throws Exception {
        String token = signupAndLogin("oauth-full@nora.dev", "SenhaForte123", "OAuth Full");

        // 1) Status inicial: google configurado, não conectado.
        JsonNode before = authGet("/integrations", token).read(HttpStatus.OK);
        JsonNode googleBefore = providerNode(before, "google");
        assertThat(googleBefore.get("configured").asBoolean()).isTrue();
        assertThat(googleBefore.get("connected").asBoolean()).isFalse();

        // 2) Start: URL de autorização com state assinado.
        JsonNode start =
                postJson("/integrations/google/oauth/start", Map.of(), token).read(HttpStatus.OK);
        String authorizeUrl = start.get("authorizeUrl").asText();
        assertThat(authorizeUrl).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(authorizeUrl).contains("client_id=client-id-teste");
        String state = queryParam(authorizeUrl, "state");
        assertThat(state).isNotBlank();

        // 3) Callback (público, como o redirect do Google faria) → 302 pro front com sucesso.
        // URI explícita: getForEntity(String) trata a string como template e re-encodaria o state.
        ResponseEntity<String> callback =
                rest.exchange(
                        callbackUri(state, "code-abc"),
                        HttpMethod.GET,
                        HttpEntity.EMPTY,
                        String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation())
                .hasToString("http://localhost:3000/integracoes?connected=google");

        // 4) Status: conectado com a conta identificada.
        JsonNode after = authGet("/integrations", token).read(HttpStatus.OK);
        JsonNode googleAfter = providerNode(after, "google");
        assertThat(googleAfter.get("connected").asBoolean()).isTrue();
        assertThat(googleAfter.get("externalAccount").asText()).isEqualTo("conta@gmail.com");

        // 5) Ação gmail_send_email num workflow: POST /test (dados de exemplo, sem reunião).
        String definition =
                """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                     "position":{"x":0,"y":0}},
                    {"id":"a1","kind":"action","type":"gmail_send_email",
                     "params":{"to":"dest@empresa.com"},"position":{"x":280,"y":0}}
                  ],
                  "edges": [{"id":"e1","source":"t1","target":"a1"}]
                }
                """;
        JsonNode wf =
                postJson(
                                "/workflows",
                                Map.of(
                                        "name",
                                        "Gmail via OAuth",
                                        "definition",
                                        mapper.readTree(definition)),
                                token)
                        .read(HttpStatus.CREATED);
        JsonNode execution =
                postJson("/workflows/" + wf.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("log").toString())
                .contains("E-mail enviado via Gmail para dest@empresa.com");
        assertThat(GMAIL.sent).hasSize(1);
        assertThat(GMAIL.sent.get(0).accessToken()).isEqualTo("at-1");
        assertThat(GMAIL.sent.get(0).to()).isEqualTo("dest@empresa.com");

        // 6) Disconnect → status volta a não conectado e a ação passa a falhar com clareza.
        ResponseEntity<String> disconnect =
                rest.exchange(
                        "/integrations/google",
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(token)),
                        String.class);
        assertThat(disconnect.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode disconnected = authGet("/integrations", token).read(HttpStatus.OK);
        assertThat(providerNode(disconnected, "google").get("connected").asBoolean()).isFalse();

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
                        "/integrations/google/oauth/callback?code=abc&state=forjado", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=integration_invalid_state");
    }

    @Test
    void callback_usuarioNegouConsentimento_redirecionaComErro() {
        ResponseEntity<String> callback =
                rest.getForEntity(
                        "/integrations/google/oauth/callback?error=access_denied", String.class);
        assertThat(callback.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(callback.getHeaders().getLocation().toString())
                .contains("/integracoes?error=access_denied");
    }

    @Test
    void conexaoEhIsoladaPorTenant() throws Exception {
        String tokenA = signupAndLogin("oauth-a@nora.dev", "SenhaForte123", "A");
        String tokenB = signupAndLogin("oauth-b@nora.dev", "SenhaForte123", "B");

        // A conecta.
        JsonNode start =
                postJson("/integrations/google/oauth/start", Map.of(), tokenA).read(HttpStatus.OK);
        String state = queryParam(start.get("authorizeUrl").asText(), "state");
        rest.exchange(callbackUri(state, "x"), HttpMethod.GET, HttpEntity.EMPTY, String.class);

        // B não vê a conexão de A.
        JsonNode statusB = authGet("/integrations", tokenB).read(HttpStatus.OK);
        assertThat(providerNode(statusB, "google").get("connected").asBoolean()).isFalse();
    }

    @Test
    void exigeAutenticacaoNoHub() {
        assertThat(rest.getForEntity("/integrations", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------------- helpers ---------------- */

    private URI callbackUri(String state, String code) {
        return URI.create(
                rest.getRootUri()
                        + "/integrations/google/oauth/callback?code="
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

    /** Captura envios Gmail em memória (substitui as chamadas HTTP reais ao Google). */
    static class RecordingGmail extends GoogleWorkspaceClient {
        record SentGmail(String accessToken, String to, String subject, String html) {}

        final List<SentGmail> sent = new CopyOnWriteArrayList<>();

        RecordingGmail() {
            super(new ObjectMapper());
        }

        @Override
        public String sendGmail(String accessToken, String to, String subject, String htmlBody) {
            sent.add(new SentGmail(accessToken, to, subject, htmlBody));
            return "gmail-msg-1";
        }

        @Override
        public String createCalendarEvent(
                String accessToken,
                String title,
                String description,
                OffsetDateTime start,
                OffsetDateTime end) {
            return "https://calendar.google.com/event-fake";
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        GoogleOAuthClient stubGoogleOAuth() {
            return new GoogleOAuthClient() {
                @Override
                public TokenResponse exchangeCode(String code, String redirectUri) {
                    return new TokenResponse("at-1", "rt-1", 3600, "openid email gmail.send");
                }

                @Override
                public TokenResponse refresh(String refreshToken) {
                    return new TokenResponse("at-2", null, 3600, null);
                }

                @Override
                public String userEmail(String accessToken) {
                    return "conta@gmail.com";
                }
            };
        }

        @Bean
        @Primary
        GoogleWorkspaceClient recordingGmail() {
            return GMAIL;
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

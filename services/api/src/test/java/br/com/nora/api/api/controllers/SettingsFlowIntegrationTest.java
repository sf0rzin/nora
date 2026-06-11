package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Fluxo end-to-end das Configurações do Core (GOAL Fase 3 item 10): GET /auth/me, PATCH /users/me,
 * POST /auth/password/change, POST /auth/logout-all, GET /tenant + PUT /tenant/name, POST
 * /auth/verify-email/resend e DELETE /users/me (LGPD hard-delete da conta + workspace pessoal).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class SettingsFlowIntegrationTest {

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

    @Test
    void me_retornaIdentidadeDoPrincipal() throws Exception {
        Session s = signupAndLogin("settings-me@nora.dev", "SenhaForte123", "Dona da Conta");

        JsonNode me = authGet("/auth/me", s.access()).read(HttpStatus.OK);
        assertThat(me.get("email").asText()).isEqualTo("settings-me@nora.dev");
        assertThat(me.get("displayName").asText()).isEqualTo("Dona da Conta");
        assertThat(me.get("emailVerified").asBoolean()).isTrue();
        assertThat(me.get("userId").asText()).isNotBlank();
        assertThat(me.get("tenantId").asText()).isNotBlank();
    }

    @Test
    void patchMe_atualizaDisplayNameDeVerdade() throws Exception {
        Session s = signupAndLogin("settings-name@nora.dev", "SenhaForte123", "Nome Antigo");

        JsonNode updated =
                exchangeJson(
                                HttpMethod.PATCH,
                                "/users/me",
                                Map.of("displayName", "Nome Novo"),
                                s.access())
                        .read(HttpStatus.OK);
        assertThat(updated.get("displayName").asText()).isEqualTo("Nome Novo");

        // Sobrevive a reload: GET /auth/me lê do banco.
        JsonNode me = authGet("/auth/me", s.access()).read(HttpStatus.OK);
        assertThat(me.get("displayName").asText()).isEqualTo("Nome Novo");

        // Blank é rejeitado.
        ResponseEntity<String> blank =
                exchangeJsonRaw(
                        HttpMethod.PATCH, "/users/me", Map.of("displayName", "  "), s.access());
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordChange_exigeSenhaAtual_revogaSessoesEAtualizaCredencial() throws Exception {
        Session s = signupAndLogin("settings-pwd@nora.dev", "SenhaForte123", "Pwd");

        // Senha atual errada → 401.
        ResponseEntity<String> wrong =
                exchangeJsonRaw(
                        HttpMethod.POST,
                        "/auth/password/change",
                        Map.of("currentPassword", "Errada123", "newPassword", "NovaForte456"),
                        s.access());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Troca de verdade → 204.
        ResponseEntity<String> ok =
                exchangeJsonRaw(
                        HttpMethod.POST,
                        "/auth/password/change",
                        Map.of("currentPassword", "SenhaForte123", "newPassword", "NovaForte456"),
                        s.access());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Dispositivo atual recebe cookies novos (não é deslogado).
        assertThat(ok.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotEmpty();

        // Sessões antigas revogadas: o refresh token do login original morreu.
        ResponseEntity<String> oldRefresh = refreshWithBearer(s.refresh());
        assertThat(oldRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Senha antiga não loga mais; a nova sim.
        ResponseEntity<String> oldLogin =
                postJsonRaw(
                        "/auth/login",
                        Map.of("email", "settings-pwd@nora.dev", "password", "SenhaForte123"),
                        null);
        assertThat(oldLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        postJson(
                        "/auth/login",
                        Map.of("email", "settings-pwd@nora.dev", "password", "NovaForte456"),
                        null)
                .read(HttpStatus.OK);
    }

    @Test
    void logoutAll_revogaTodasAsSessoes() throws Exception {
        Session s1 = signupAndLogin("settings-all@nora.dev", "SenhaForte123", "All");
        // Segunda sessão (outro dispositivo).
        JsonNode login2 =
                postJson(
                                "/auth/login",
                                Map.of(
                                        "email",
                                        "settings-all@nora.dev",
                                        "password",
                                        "SenhaForte123"),
                                null)
                        .read(HttpStatus.OK);
        String refresh2 = login2.get("refreshToken").asText();

        ResponseEntity<String> resp =
                exchangeJsonRaw(HttpMethod.POST, "/auth/logout-all", Map.of(), s1.access());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(refreshWithBearer(s1.refresh()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshWithBearer(refresh2).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tenant_leERenomeiaWorkspace() throws Exception {
        Session s = signupAndLogin("settings-ws@nora.dev", "SenhaForte123", "Workspace Dona");

        JsonNode tenant = authGet("/tenant", s.access()).read(HttpStatus.OK);
        assertThat(tenant.get("name").asText()).contains("Workspace Dona");
        String slugAntes = tenant.get("slug").asText();
        assertThat(tenant.get("plan").asText()).isNotBlank();

        JsonNode renamed =
                exchangeJson(
                                HttpMethod.PUT,
                                "/tenant/name",
                                Map.of("name", "Time Foguete"),
                                s.access())
                        .read(HttpStatus.OK);
        assertThat(renamed.get("name").asText()).isEqualTo("Time Foguete");
        assertThat(renamed.get("slug").asText()).isEqualTo(slugAntes);

        JsonNode after = authGet("/tenant", s.access()).read(HttpStatus.OK);
        assertThat(after.get("name").asText()).isEqualTo("Time Foguete");

        // Blank rejeitado.
        assertThat(
                        exchangeJsonRaw(
                                        HttpMethod.PUT,
                                        "/tenant/name",
                                        Map.of("name", " "),
                                        s.access())
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void resendVerification_reenviaParaNaoVerificado_eNaoVazaContas() throws Exception {
        // Signup SEM verificar.
        postJson(
                        "/auth/signup",
                        Map.of(
                                "email",
                                "settings-resend@nora.dev",
                                "password",
                                "SenhaForte123",
                                "displayName",
                                "Resend"),
                        null)
                .read(HttpStatus.CREATED);

        // Reenvio gera token novo (devToken exposto no profile de teste).
        JsonNode resent =
                postJson(
                                "/auth/verify-email/resend",
                                Map.of("email", "settings-resend@nora.dev"),
                                null)
                        .read(HttpStatus.ACCEPTED);
        String newToken = resent.get("verificationDevToken").asText();
        assertThat(newToken).isNotBlank();

        // Token novo verifica e o login passa a funcionar.
        postJson("/auth/verify-email", Map.of("token", newToken), null).read(HttpStatus.NO_CONTENT);
        postJson(
                        "/auth/login",
                        Map.of("email", "settings-resend@nora.dev", "password", "SenhaForte123"),
                        null)
                .read(HttpStatus.OK);

        // Já verificado e inexistente: mesma resposta 202 sem devToken (anti-enumeração).
        JsonNode verified =
                postJson(
                                "/auth/verify-email/resend",
                                Map.of("email", "settings-resend@nora.dev"),
                                null)
                        .read(HttpStatus.ACCEPTED);
        assertThat(verified.get("verificationDevToken").isNull()).isTrue();
        JsonNode unknown =
                postJson("/auth/verify-email/resend", Map.of("email", "naoexiste@nora.dev"), null)
                        .read(HttpStatus.ACCEPTED);
        assertThat(unknown.get("verificationDevToken").isNull()).isTrue();
    }

    @Test
    void deleteAccount_exigeSenha_ePurgaContaEWorkspace() throws Exception {
        Session s = signupAndLogin("settings-del@nora.dev", "SenhaForte123", "Del");

        // Senha errada → 401 e nada apagado.
        ResponseEntity<String> wrong =
                exchangeJsonRaw(
                        HttpMethod.DELETE,
                        "/users/me",
                        Map.of("password", "Errada123"),
                        s.access());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        authGet("/auth/me", s.access()).read(HttpStatus.OK);

        // Senha certa → 204 e a conta SOME (hard-delete em cascata do tenant pessoal).
        ResponseEntity<String> ok =
                exchangeJsonRaw(
                        HttpMethod.DELETE,
                        "/users/me",
                        Map.of("password", "SenhaForte123"),
                        s.access());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // JWT ainda é estaticamente válido, mas o usuário não existe mais → 401.
        assertThat(authGetRaw("/auth/me", s.access()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Login impossivel: credenciais apagadas.
        assertThat(
                        postJsonRaw(
                                        "/auth/login",
                                        Map.of(
                                                "email",
                                                "settings-del@nora.dev",
                                                "password",
                                                "SenhaForte123"),
                                        null)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // E-mail liberado para um novo signup (esquecimento de verdade).
        postJson(
                        "/auth/signup",
                        Map.of(
                                "email",
                                "settings-del@nora.dev",
                                "password",
                                "OutraForte123",
                                "displayName",
                                "Renascida"),
                        null)
                .read(HttpStatus.CREATED);
    }

    @Test
    void endpointsExigemAutenticacao() {
        assertThat(authGetRaw("/auth/me", "invalido").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(
                        rest.exchange(
                                        "/tenant",
                                        HttpMethod.GET,
                                        new HttpEntity<>(new HttpHeaders()),
                                        String.class)
                                .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    private record Session(String access, String refresh) {}

    private Session signupAndLogin(String email, String pwd, String name) throws Exception {
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
        return new Session(login.get("accessToken").asText(), login.get("refreshToken").asText());
    }

    /** POST /auth/refresh usando o fallback Bearer (o cookie é o caminho do browser). */
    private ResponseEntity<String> refreshWithBearer(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);
        return rest.exchange(
                "/auth/refresh", HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private RequestExec postJson(String path, Map<String, ?> body, String token) throws Exception {
        return new RequestExec(postJsonRaw(path, body, token));
    }

    private ResponseEntity<String> postJsonRaw(String path, Map<String, ?> body, String token)
            throws Exception {
        return exchangeJsonRaw(HttpMethod.POST, path, body, token);
    }

    private RequestExec exchangeJson(
            HttpMethod method, String path, Map<String, ?> body, String token) throws Exception {
        return new RequestExec(exchangeJsonRaw(method, path, body, token));
    }

    private ResponseEntity<String> exchangeJsonRaw(
            HttpMethod method, String path, Map<String, ?> body, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return rest.exchange(path, method, entity, String.class);
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(authGetRaw(path, token));
    }

    private ResponseEntity<String> authGetRaw(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
}

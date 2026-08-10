package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
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
 * End-to-end flow of the Core Settings (GOAL Phase 3 item 10): GET /auth/me, PATCH /users/me, POST
 * /auth/password/change, POST /auth/logout-all, GET /tenant + PUT /tenant/name, POST
 * /auth/verify-email/resend and DELETE /users/me (LGPD hard-delete of the account + personal
 * workspace).
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

        // Survives a reload: GET /auth/me reads from the database.
        JsonNode me = authGet("/auth/me", s.access()).read(HttpStatus.OK);
        assertThat(me.get("displayName").asText()).isEqualTo("Nome Novo");

        // Blank is rejected.
        ResponseEntity<String> blank =
                exchangeJsonRaw(
                        HttpMethod.PATCH, "/users/me", Map.of("displayName", "  "), s.access());
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void passwordChange_exigeSenhaAtual_revogaSessoesEAtualizaCredencial() throws Exception {
        Session s = signupAndLogin("settings-pwd@nora.dev", "SenhaForte123", "Pwd");

        // Wrong current password → 401.
        ResponseEntity<String> wrong =
                exchangeJsonRaw(
                        HttpMethod.POST,
                        "/auth/password/change",
                        Map.of("currentPassword", "Errada123", "newPassword", "NovaForte456"),
                        s.access());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Real change → 204.
        ResponseEntity<String> ok =
                exchangeJsonRaw(
                        HttpMethod.POST,
                        "/auth/password/change",
                        Map.of("currentPassword", "SenhaForte123", "newPassword", "NovaForte456"),
                        s.access());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // The current device gets fresh cookies (it is not logged out).
        assertThat(ok.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotEmpty();

        // Old sessions revoked: the refresh token from the original login is dead.
        ResponseEntity<String> oldRefresh = refreshWithBearer(s.refresh());
        assertThat(oldRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // The old password no longer logs in; the new one does.
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
        // Second session (another device).
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

        // Blank rejected.
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
        // Signup WITHOUT verifying.
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

        // Resend generates a new token (devToken exposed in the test profile).
        JsonNode resent =
                postJson(
                                "/auth/verify-email/resend",
                                Map.of("email", "settings-resend@nora.dev"),
                                null)
                        .read(HttpStatus.ACCEPTED);
        String newToken = resent.get("verificationDevToken").asText();
        assertThat(newToken).isNotBlank();

        // The new token verifies and login starts working.
        postJson("/auth/verify-email", Map.of("token", newToken), null).read(HttpStatus.NO_CONTENT);
        postJson(
                        "/auth/login",
                        Map.of("email", "settings-resend@nora.dev", "password", "SenhaForte123"),
                        null)
                .read(HttpStatus.OK);

        // Already verified and unknown: same 202 response with no devToken (anti-enumeration).
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

    /**
     * Issue #399. Unknown address, already verified address and pending address must be one single
     * answer over the wire: same status, same field set, same message. The dev token is the only
     * value that differs, and only because {@code expose-dev-tokens} is on in the test profile — in
     * production it is off and every branch returns the same body byte for byte.
     */
    @Test
    void resendVerification_respondeIgualParaDesconhecidoVerificadoENaoVerificado()
            throws Exception {
        postJson(
                        "/auth/signup",
                        Map.of(
                                "email",
                                "resend-p@nora.dev",
                                "password",
                                "SenhaForte123",
                                "displayName",
                                "Pendente"),
                        null)
                .read(HttpStatus.CREATED);
        signupAndLogin("resend-ok@nora.dev", "SenhaForte123", "Verificada");

        ResponseEntity<String> unknown =
                postJsonRaw(
                        "/auth/verify-email/resend", Map.of("email", "resend-x@nora.dev"), null);
        ResponseEntity<String> verified =
                postJsonRaw(
                        "/auth/verify-email/resend", Map.of("email", "resend-ok@nora.dev"), null);
        ResponseEntity<String> pending =
                postJsonRaw(
                        "/auth/verify-email/resend", Map.of("email", "resend-p@nora.dev"), null);

        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(verified.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(pending.getStatusCode()).isEqualTo(unknown.getStatusCode());

        JsonNode unknownBody = mapper.readTree(unknown.getBody());
        JsonNode verifiedBody = mapper.readTree(verified.getBody());
        JsonNode pendingBody = mapper.readTree(pending.getBody());
        assertThat(fieldNames(verifiedBody)).isEqualTo(fieldNames(unknownBody));
        assertThat(fieldNames(pendingBody)).isEqualTo(fieldNames(unknownBody));
        assertThat(verifiedBody.get("message").asText())
                .isEqualTo(unknownBody.get("message").asText());
        assertThat(pendingBody.get("message").asText())
                .isEqualTo(unknownBody.get("message").asText());
    }

    /**
     * The resend payload is bounded like its siblings. It matters here more than on a form field:
     * the value is used as a rate-limiter bucket key, and the limiter holds its keys in memory for
     * the length of the window, so what a caller can put there has to be bounded before it lands.
     */
    @Test
    void resendVerification_rejeitaEmailMalformadoOuLongoDemais() throws Exception {
        ResponseEntity<String> malformed =
                postJsonRaw("/auth/verify-email/resend", Map.of("email", "nao-e-email"), null);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        String tooLong = "a".repeat(250) + "@nora.dev";
        ResponseEntity<String> oversized =
                postJsonRaw("/auth/verify-email/resend", Map.of("email", tooLong), null);
        assertThat(oversized.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deleteAccount_exigeSenha_ePurgaContaEWorkspace() throws Exception {
        Session s = signupAndLogin("settings-del@nora.dev", "SenhaForte123", "Del");

        // Wrong password → 401 and nothing deleted.
        ResponseEntity<String> wrong =
                exchangeJsonRaw(
                        HttpMethod.DELETE,
                        "/users/me",
                        Map.of("password", "Errada123"),
                        s.access());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        authGet("/auth/me", s.access()).read(HttpStatus.OK);

        // Right password → 204 and the account is GONE (cascading hard-delete of the personal
        // tenant).
        ResponseEntity<String> ok =
                exchangeJsonRaw(
                        HttpMethod.DELETE,
                        "/users/me",
                        Map.of("password", "SenhaForte123"),
                        s.access());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The JWT is still statically valid, but the user no longer exists → 401.
        assertThat(authGetRaw("/auth/me", s.access()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        // Login impossible: credentials deleted.
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
        // E-mail freed up for a new signup (real erasure).
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

    /** Sorted field names of a JSON object — compares body SHAPE without comparing values. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
    }

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

    /** POST /auth/refresh using the Bearer fallback (the cookie is the browser path). */
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

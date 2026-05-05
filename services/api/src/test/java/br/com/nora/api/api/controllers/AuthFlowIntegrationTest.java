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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Teste end-to-end do fluxo completo US01 -> US02 -> US03 -> US04. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthFlowIntegrationTest {

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

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    /**
     * O SimpleClientHttpRequestFactory padrao do TestRestTemplate transmite o body em modo
     * streaming. Quando o servidor responde 401 (caso esperado em login antes da verificacao), o
     * HttpURLConnection tenta retentar com autenticacao e estoura HttpRetryException porque o body
     * ja foi enviado. Desligamos o streaming para que respostas 401 sejam lidas normalmente.
     */
    @BeforeEach
    void disableOutputStreaming() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        rest.getRestTemplate().setRequestFactory(factory);
    }

    @Test
    void completeAuthLifecycle() throws Exception {
        String email = "lucas+e2e@nora.dev";

        // US01: signup
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        "SenhaForte123",
                                        "displayName",
                                        "Lucas E2E"))
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        assertThat(verifyToken).isNotBlank();

        // US03 antes de verificar -> 401 EMAIL_NOT_VERIFIED
        JsonNode loginBefore =
                postJson("/auth/login", Map.of("email", email, "password", "SenhaForte123"))
                        .read(HttpStatus.UNAUTHORIZED);
        assertThat(loginBefore.get("code").asText()).isEqualTo("EMAIL_NOT_VERIFIED");

        // US02: verify
        postJson("/auth/verify-email", Map.of("token", verifyToken)).read(HttpStatus.NO_CONTENT);

        // US03: login OK
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", "SenhaForte123"))
                        .read(HttpStatus.OK);
        String accessToken = login.get("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        assertThat(login.get("tokenType").asText()).isEqualTo("Bearer");

        // Endpoint privado aceita o token
        HttpHeaders authHdr = new HttpHeaders();
        authHdr.set("Authorization", "Bearer " + accessToken);
        ResponseEntity<String> probe =
                rest.exchange(
                        "/auth/login-probe-not-existing",
                        HttpMethod.GET,
                        new HttpEntity<>(authHdr),
                        String.class);
        // Rota nao existe (404), mas o ponto e que NAO recebemos 401: passou pelo filtro JWT.
        assertThat(probe.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // US04 step 1: request reset
        JsonNode reqReset =
                postJson("/auth/password/reset/request", Map.of("email", email))
                        .read(HttpStatus.ACCEPTED);
        String resetToken = reqReset.get("passwordResetDevToken").asText();
        assertThat(resetToken).isNotBlank();

        // US04 step 2: confirm
        postJson(
                        "/auth/password/reset/confirm",
                        Map.of("token", resetToken, "newPassword", "OutraSenha456"))
                .read(HttpStatus.NO_CONTENT);

        // Senha antiga falha
        JsonNode oldPwd =
                postJson("/auth/login", Map.of("email", email, "password", "SenhaForte123"))
                        .read(HttpStatus.UNAUTHORIZED);
        assertThat(oldPwd.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        // Nova senha funciona
        postJson("/auth/login", Map.of("email", email, "password", "OutraSenha456"))
                .read(HttpStatus.OK);
    }

    @Test
    void signupRejectsDuplicate() throws Exception {
        String email = "dup@nora.dev";
        postJson(
                        "/auth/signup",
                        Map.of("email", email, "password", "SenhaForte123", "displayName", "X"))
                .read(HttpStatus.CREATED);
        JsonNode dup =
                postJson(
                                "/auth/signup",
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        "SenhaForte123",
                                        "displayName",
                                        "Y"))
                        .read(HttpStatus.CONFLICT);
        assertThat(dup.get("code").asText()).isEqualTo("EMAIL_ALREADY_TAKEN");
    }

    @Test
    void signupValidatesPayload() throws Exception {
        JsonNode bad =
                postJson(
                                "/auth/signup",
                                Map.of(
                                        "email",
                                        "not-email",
                                        "password",
                                        "short",
                                        "displayName",
                                        ""))
                        .read(HttpStatus.BAD_REQUEST);
        assertThat(bad.get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unknownEmailResetReturns202WithoutLeak() throws Exception {
        JsonNode r =
                postJson("/auth/password/reset/request", Map.of("email", "ghost@nora.dev"))
                        .read(HttpStatus.ACCEPTED);
        assertThat(r.has("passwordResetDevToken")).isFalse();
    }

    private RequestExec postJson(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
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

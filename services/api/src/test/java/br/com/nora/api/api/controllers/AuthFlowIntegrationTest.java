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
import org.springframework.boot.test.web.server.LocalServerPort;
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

/** End-to-end test of the complete US01 -> US02 -> US03 -> US04 flow. */
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
     * TestRestTemplate's default SimpleClientHttpRequestFactory uses the legacy HttpURLConnection,
     * which throws {@code HttpRetryException} when it gets a 401 (an expected case on login before
     * verification). We swap it for the Java 11 HttpClient-based implementation, which handles 401
     * normally.
     */
    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
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

        // US03 before verifying -> 401 EMAIL_NOT_VERIFIED
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

        // Private endpoint accepts the token
        HttpHeaders authHdr = new HttpHeaders();
        authHdr.set("Authorization", "Bearer " + accessToken);
        ResponseEntity<String> probe =
                rest.exchange(
                        "/auth/login-probe-not-existing",
                        HttpMethod.GET,
                        new HttpEntity<>(authHdr),
                        String.class);
        // The route does not exist (404), but the point is that we did NOT get 401: it passed the
        // JWT filter.
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

        // Old password fails
        JsonNode oldPwd =
                postJson("/auth/login", Map.of("email", email, "password", "SenhaForte123"))
                        .read(HttpStatus.UNAUTHORIZED);
        assertThat(oldPwd.get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        // New password works
        postJson("/auth/login", Map.of("email", email, "password", "OutraSenha456"))
                .read(HttpStatus.OK);
    }

    /**
     * Replaces the old {@code signupRejectsDuplicate}, which asserted a 409 EMAIL_ALREADY_TAKEN on
     * the second signup. That status WAS the finding: one request per candidate address turned a
     * public endpoint into a directory of who has an account. Over the wire the two calls must now
     * be the same answer, and the second one must leave the database as it was.
     */
    @Test
    void signupOnRegisteredAddressAnswersLikeAFreshSignup() throws Exception {
        String email = "dup@nora.dev";
        JsonNode first =
                postJson(
                                "/auth/signup",
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        "SenhaForte123",
                                        "displayName",
                                        "X"))
                        .read(HttpStatus.CREATED);

        JsonNode again =
                postJson(
                                "/auth/signup",
                                Map.of(
                                        "email",
                                        email,
                                        "password",
                                        "OutraSenha456",
                                        "displayName",
                                        "Y"))
                        .read(HttpStatus.CREATED);

        // Same status (asserted by read), same field set, same message, and ids that are not
        // reused — nothing in the response separates the two calls.
        assertThat(fieldNames(again)).isEqualTo(fieldNames(first));
        assertThat(again.get("message").asText()).isEqualTo(first.get("message").asText());
        assertThat(again.get("userId").asText()).isNotEqualTo(first.get("userId").asText());
        assertThat(again.get("tenantId").asText()).isNotEqualTo(first.get("tenantId").asText());

        // No second account: the token handed back by the second call verifies nothing.
        JsonNode rejected =
                postJson(
                                "/auth/verify-email",
                                Map.of("token", again.get("emailVerificationDevToken").asText()))
                        .read(HttpStatus.BAD_REQUEST);
        assertThat(rejected.get("code").asText()).isEqualTo("TOKEN_INVALID");

        // The original account is intact: its own token still verifies, its own password still
        // logs in, and the password sent on the second attempt does not.
        postJson(
                        "/auth/verify-email",
                        Map.of("token", first.get("emailVerificationDevToken").asText()))
                .read(HttpStatus.NO_CONTENT);
        postJson("/auth/login", Map.of("email", email, "password", "OutraSenha456"))
                .read(HttpStatus.UNAUTHORIZED);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", "SenhaForte123"))
                        .read(HttpStatus.OK);
        assertThat(login.get("userId").asText()).isEqualTo(first.get("userId").asText());
        assertThat(login.get("displayName").asText()).isEqualTo("X");
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

    /** Sorted field names of a JSON object — compares body SHAPE without comparing values. */
    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        return names;
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

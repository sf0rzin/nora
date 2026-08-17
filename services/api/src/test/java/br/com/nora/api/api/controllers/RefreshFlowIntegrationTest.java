package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * End-to-end coverage of the stateful refresh flow (Round 2 / Subphase 1.3 A).
 *
 * <ul>
 *   <li>Login sets httpOnly cookies {@code nora_access} (Path=/, Lax) and {@code nora_refresh}
 *       (Path=/auth, Strict).
 *   <li>{@code POST /auth/refresh} without a cookie -> 401 REFRESH_TOKEN_INVALID.
 *   <li>{@code POST /auth/refresh} with a valid cookie -> 200 with a new access cookie.
 *   <li>{@code POST /auth/logout} revokes the refresh; a subsequent refresh fails with 401.
 *   <li>Login after logout requires credentials again (real revocation, not client-only).
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RefreshFlowIntegrationTest {

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
        // Refresh long enough that the test does not race; access stays short.
        registry.add("nora.security.refresh-token.expires-seconds", () -> 3600);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void loginSetsHttpOnlyCookies() throws Exception {
        String email = "ck@nora.dev";
        registerAndVerify(email);

        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> setCookies = setCookieHeaders(login);
        assertThat(setCookies).hasSize(2);

        String access = findCookie(setCookies, "nora_access").orElseThrow();
        String refresh = findCookie(setCookies, "nora_refresh").orElseThrow();

        assertThat(access).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
        assertThat(refresh).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/auth");

        // Body keeps accessToken for back-compat.
        JsonNode body = mapper.readTree(login.getBody());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("expiresInSeconds").asLong()).isGreaterThan(0);
    }

    @Test
    void refreshWithoutCookieReturns401() throws Exception {
        ResponseEntity<String> r = postWithCookies("/auth/refresh", List.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void refreshWithAuthorizationHeaderReturns200() throws Exception {
        String email = "hdr@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Desktop does not use a cookie; it reads refreshToken from the body.
        JsonNode loginBody = mapper.readTree(login.getBody());
        String refreshToken = loginBody.get("refreshToken").asText();
        assertThat(refreshToken).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(refreshToken);
        HttpEntity<String> entity = new HttpEntity<>("", headers);
        ResponseEntity<String> r =
                rest.exchange("/auth/refresh", HttpMethod.POST, entity, String.class);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresInSeconds").asLong()).isGreaterThan(0);
        // Also sets a new access cookie (back-compat with web).
        assertThat(findCookie(setCookieHeaders(r), "nora_access")).isPresent();
    }

    @Test
    void refreshRotatesAndToleratesBenignReuseWithinLeeway() throws Exception {
        // Audit follow-up #3 + benign race (PR #238): every /auth/refresh rotates the
        // cookie. Re-presenting the old cookie RIGHT AFTER the rotation (multi-tab, timer +
        // 401 interceptor) is treated as a benign race inside the 60s window: it issues a
        // new pair in the same family instead of revoking everything and logging the user out.
        // The revocation OUTSIDE the window is exercised by
        // reuseOutsideTheLeewayRevokesTheWholeFamilyForReal below, against this same database.
        // It used to be delegated to AuthServiceTest's fake clock on the grounds that the clock
        // here is real and 60s is too long to wait — and that delegation is precisely what let a
        // rollback bug hide for as long as it did, because a fake repository has no transaction.
        // Backdating last_used_at clears the window without waiting.
        String email = "rt@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        List<String> loginCookies = setCookieHeaders(login);
        String oldRefresh = extractCookieValue(loginCookies, "nora_refresh");

        // First refresh call
        ResponseEntity<String> r1 =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + oldRefresh));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body1 = mapper.readTree(r1.getBody());
        assertThat(body1.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body1.get("expiresInSeconds").asLong()).isGreaterThan(0);
        // Set a new access cookie AND a new refresh cookie (rotation).
        List<String> r1Cookies = setCookieHeaders(r1);
        assertThat(findCookie(r1Cookies, "nora_access")).isPresent();
        String newRefresh = extractCookieValue(r1Cookies, "nora_refresh");
        assertThat(newRefresh).isNotBlank().isNotEqualTo(oldRefresh);

        // Re-presenting the old refresh immediately = benign race => 200 with a new pair
        // in the same family (the "second tab" stays logged in with its own cookie).
        ResponseEntity<String> reuse =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + oldRefresh));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String racedRefresh = extractCookieValue(setCookieHeaders(reuse), "nora_refresh");
        assertThat(racedRefresh).isNotBlank().isNotEqualTo(oldRefresh).isNotEqualTo(newRefresh);

        // Both children (normal rotation and race) remain valid on the next rotation —
        // the family was NOT revoked.
        ResponseEntity<String> r2 =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + newRefresh));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> r3 =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + racedRefresh));
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Reuse OUTSIDE the leeway must actually kill the family — in the database, not just in the
     * log line that says so.
     *
     * <p>This is a regression test for a control that reported itself as having acted while
     * undoing its own work. {@code AuthService.refresh} revokes the whole family and then throws
     * {@code RefreshTokenInvalid}, which is a RuntimeException; under a plain {@code @Transactional}
     * the throw rolled the revocation back. Production logged {@code Refresh token reuse detected}
     * and every token in the family kept working, so a stolen chain survived the detection that
     * exists to cut it. Verified against the live deployment before the fix: the presented token
     * got its 401, the WARN was in the log, and the CURRENT token of the same family still
     * refreshed successfully.
     *
     * <p>The existing coverage could not see it. {@code AuthServiceTest} drives an in-memory fake
     * repository where "revoke" is a mutation of a list and there is no transaction to roll back,
     * so the revocation always sticks. It needs a real database, which is why it lives here.
     *
     * <p>The 60-second window is cleared by BACKDATING {@code last_used_at} rather than by
     * sleeping — the leeway is anchored to that column, so moving it is the same input a real
     * hour-old stolen cookie provides.
     */
    @Test
    void reuseOutsideTheLeewayRevokesTheWholeFamilyForReal() throws Exception {
        String email = "reuse@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        String stolen = extractCookieValue(setCookieHeaders(login), "nora_refresh");

        ResponseEntity<String> rotated =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + stolen));
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String current = extractCookieValue(setCookieHeaders(rotated), "nora_refresh");
        assertThat(current).isNotBlank().isNotEqualTo(stolen);

        // Push every last_used_at well outside REFRESH_REUSE_LEEWAY so the next presentation is
        // read as theft rather than as a multi-tab race.
        try (java.sql.Connection c =
                        java.sql.DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement s = c.createStatement()) {
            // Scoped to this test's user: the container is shared, and an unscoped UPDATE would
            // reach into whatever other tests in this class have left lying around.
            s.executeUpdate(
                    "UPDATE refresh_tokens SET last_used_at = NOW() - INTERVAL '10 minutes'"
                            + " WHERE user_id = (SELECT id FROM users WHERE email = '"
                            + email
                            + "')");
        }

        ResponseEntity<String> theft =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + stolen));
        assertThat(theft.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // THE ASSERTION THAT MATTERS. Refusing the presented token proves only that it was
        // already revoked by rotation — it would have been refused anyway. What the detection
        // is FOR is cutting the rest of the chain, so the victim's live token must be dead too.
        ResponseEntity<String> afterDetection =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + current));
        assertThat(afterDetection.getStatusCode())
                .as("the current token of a family flagged for reuse must be revoked too")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // And it is persisted, not merely in the session's head.
        try (java.sql.Connection c =
                        java.sql.DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement s = c.createStatement();
                java.sql.ResultSet rs =
                        s.executeQuery(
                                "SELECT COUNT(*) FROM refresh_tokens WHERE revoked_at IS NULL"
                                        + " AND user_id = (SELECT id FROM users WHERE email = '"
                                        + email
                                        + "')")) {
            rs.next();
            assertThat(rs.getInt(1))
                    .as("no token of the family may survive the reuse detection")
                    .isZero();
        }
    }

    @Test
    void logoutRevokesRefreshAndSubsequentRefreshFails() throws Exception {
        String email = "out@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        String refreshCookieValue = extractCookieValue(setCookieHeaders(login), "nora_refresh");

        ResponseEntity<String> logout =
                postWithCookies("/auth/logout", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // Clearing cookies were set.
        List<String> clearCookies = setCookieHeaders(logout);
        assertThat(clearCookies).hasSize(2);
        assertThat(
                        clearCookies.stream()
                                .anyMatch(
                                        c ->
                                                c.startsWith("nora_access=")
                                                        && c.contains("Max-Age=0")))
                .isTrue();
        assertThat(
                        clearCookies.stream()
                                .anyMatch(
                                        c ->
                                                c.startsWith("nora_refresh=")
                                                        && c.contains("Max-Age=0")))
                .isTrue();

        // Trying to refresh with the revoked token must fail 401.
        ResponseEntity<String> r =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode body = mapper.readTree(r.getBody());
        assertThat(body.get("code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void logoutWithoutCookieIsNoOp204() throws Exception {
        ResponseEntity<String> r = postWithCookies("/auth/logout", List.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void refreshWithGarbageCookieReturns401() throws Exception {
        ResponseEntity<String> r =
                postWithCookies("/auth/refresh", List.of("nora_refresh=lixo-aleatorio"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginAfterLogoutStillRequiresCredentials() throws Exception {
        String email = "rl@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        String refreshCookieValue = extractCookieValue(setCookieHeaders(login), "nora_refresh");

        // Logout
        postWithCookies("/auth/logout", List.of("nora_refresh=" + refreshCookieValue));

        // A new login needs credentials (POST without body -> 400 validation).
        ResponseEntity<String> badLogin = postJson("/auth/login", Map.of());
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Real credentials work and produce a new refresh (different from the revoked one).
        ResponseEntity<String> ok = postJson("/auth/login", basicAuth(email));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = extractCookieValue(setCookieHeaders(ok), "nora_refresh");
        assertThat(newRefresh).isNotEqualTo(refreshCookieValue);

        // The old refresh remains invalid.
        ResponseEntity<String> oldRefresh =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(oldRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The new refresh works.
        ResponseEntity<String> newRefreshResp =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + newRefresh));
        assertThat(newRefreshResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ----- helpers -----

    private void registerAndVerify(String email) throws Exception {
        ResponseEntity<String> signup =
                postJson(
                        "/auth/signup",
                        Map.of(
                                "email",
                                email,
                                "password",
                                "SenhaForte123",
                                "displayName",
                                "Test User"));
        assertThat(signup.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String verifyToken =
                mapper.readTree(signup.getBody()).get("emailVerificationDevToken").asText();
        ResponseEntity<String> v = postJson("/auth/verify-email", Map.of("token", verifyToken));
        assertThat(v.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private Map<String, String> basicAuth(String email) {
        return Map.of("email", email, "password", "SenhaForte123");
    }

    private ResponseEntity<String> postJson(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return rest.postForEntity(path, entity, String.class);
    }

    private ResponseEntity<String> postWithCookies(String path, List<String> cookies) {
        HttpHeaders headers = new HttpHeaders();
        if (!cookies.isEmpty()) {
            headers.add(HttpHeaders.COOKIE, String.join("; ", cookies));
        }
        HttpEntity<String> entity = new HttpEntity<>("", headers);
        return rest.exchange(path, HttpMethod.POST, entity, String.class);
    }

    private static List<String> setCookieHeaders(ResponseEntity<?> resp) {
        List<String> v = resp.getHeaders().get(HttpHeaders.SET_COOKIE);
        return v == null ? List.of() : v;
    }

    private static Optional<String> findCookie(List<String> setCookies, String name) {
        return setCookies.stream().filter(c -> c.startsWith(name + "=")).findFirst();
    }

    private static String extractCookieValue(List<String> setCookies, String name) {
        String raw = findCookie(setCookies, name).orElseThrow();
        // format: name=value; Path=...; ...
        String afterEq = raw.substring(name.length() + 1);
        int semi = afterEq.indexOf(';');
        return semi < 0 ? afterEq : afterEq.substring(0, semi);
    }
}

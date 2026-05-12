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
 * Cobertura end-to-end do fluxo de refresh stateful (Round 2 / Subfase 1.3 A).
 *
 * <ul>
 *   <li>Login seta cookies httpOnly {@code nora_access} (Path=/, Lax) e {@code nora_refresh}
 *       (Path=/auth, Strict).
 *   <li>{@code POST /auth/refresh} sem cookie -> 401 REFRESH_TOKEN_INVALID.
 *   <li>{@code POST /auth/refresh} com cookie valido -> 200 com novo cookie de access.
 *   <li>{@code POST /auth/logout} revoga o refresh; refresh subsequente falha com 401.
 *   <li>Login apos logout exige credenciais novamente (revogacao real, nao client-only).
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
        // Refresh longo o suficiente pra o teste nao racear; access continua curto.
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

        // Body mantem accessToken pra back-compat.
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
    void refreshRotatesAccessButKeepsRefreshValid() throws Exception {
        String email = "rt@nora.dev";
        registerAndVerify(email);
        ResponseEntity<String> login = postJson("/auth/login", basicAuth(email));
        List<String> loginCookies = setCookieHeaders(login);
        String refreshCookieValue = extractCookieValue(loginCookies, "nora_refresh");

        // Primeira chamada de refresh
        ResponseEntity<String> r1 =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body1 = mapper.readTree(r1.getBody());
        assertThat(body1.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body1.get("expiresInSeconds").asLong()).isGreaterThan(0);
        // Setou um novo cookie de access
        List<String> r1Cookies = setCookieHeaders(r1);
        assertThat(findCookie(r1Cookies, "nora_access")).isPresent();

        // Segunda chamada com o MESMO refresh tem que continuar funcionando (sem rotacao).
        ResponseEntity<String> r2 =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);
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
        // Cookies de limpeza foram setados.
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

        // Tentar refresh com o token revogado deve falhar 401.
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

        // Login novo precisa de credentials (POST sem body -> 400 validation).
        ResponseEntity<String> badLogin = postJson("/auth/login", Map.of());
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // Com credentials reais funciona e produz um refresh novo (diferente do revogado).
        ResponseEntity<String> ok = postJson("/auth/login", basicAuth(email));
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newRefresh = extractCookieValue(setCookieHeaders(ok), "nora_refresh");
        assertThat(newRefresh).isNotEqualTo(refreshCookieValue);

        // Refresh velho continua invalido.
        ResponseEntity<String> oldRefresh =
                postWithCookies("/auth/refresh", List.of("nora_refresh=" + refreshCookieValue));
        assertThat(oldRefresh.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Novo refresh funciona.
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
        // formato: name=value; Path=...; ...
        String afterEq = raw.substring(name.length() + 1);
        int semi = afterEq.indexOf(';');
        return semi < 0 ? afterEq : afterEq.substring(0, semi);
    }
}

package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Version history of the company context (US31, migration V028).
 *
 * <p>What these tests are for. The context document is the input that makes an analysis specific to
 * the customer's business, and until V028 every {@code PUT /tenant/context} overwrote it with no
 * trace. The feature is only worth anything if three things hold at once: the write path records a
 * version, the read path can retrieve it, and neither leaks across the tenant boundary.
 *
 * <p>The third is the one that needs a test rather than an argument. Row-level security IS defined
 * on {@code tenant_context_versions} (V028) but is off in this configuration — Testcontainers
 * connects as the table owner, which bypasses RLS — so the tenant predicate in the repository query
 * is the only control here, exactly as in {@code TenantSettingsIsolationIntegrationTest}. The
 * intruder is the other tenant's Root, so the IAM gate cannot be what refuses: Root short-circuits
 * {@code AuthorizationService}, and the ARN the interceptor checks is built from the caller's own
 * claim.
 *
 * <p>What is NOT covered here, stated rather than implied: the migration's backfill of version 1
 * for contexts that already existed. Flyway runs before any of these tests can write a pre-V028
 * row, so there is nothing for a test to observe. It is verified by construction — the INSERT in
 * V028 selects every row of {@code tenant_contexts}, and {@code current_version} defaults to 1 on
 * the same rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantContextHistoryIntegrationTest {

    private static final String PASSWORD = "SenhaForte123";

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
    void everyEditIsRecorded_andAPastVersionStillReturnsWhatItFroze() throws Exception {
        String token = signupAndLogin("hist-record@nora.dev", "Alfa");

        // Nothing saved yet: an empty history, not a 404 and not a null body.
        JsonNode empty = read(authGet("/tenant/context/versions", token), HttpStatus.OK);
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("total").asInt()).isZero();
        assertThat(empty.get("currentVersion").asInt()).isZero();

        put(
                "/tenant/context",
                json(Map.of("companyName", "Alfa", "competitors", List.of("Beta"))),
                token);
        put(
                "/tenant/context",
                json(Map.of("companyName", "Alfa Tecnologia", "competitors", List.of("Gama"))),
                token);

        JsonNode history = read(authGet("/tenant/context/versions", token), HttpStatus.OK);
        assertThat(history.get("total").asInt()).isEqualTo(2);
        assertThat(history.get("currentVersion").asInt()).isEqualTo(2);
        // Newest first — the list is read top-down and the current version is what a reader
        // expects to see at the top.
        assertThat(history.get("items").get(0).get("version").asInt()).isEqualTo(2);
        assertThat(history.get("items").get(1).get("version").asInt()).isEqualTo(1);
        // The author travels with the entry: a trail that cannot say who is half a trail.
        assertThat(history.get("items").get(0).get("createdByName").asText()).isEqualTo("Alfa");
        assertThat(history.get("items").get(0).get("createdBy").isNull()).isFalse();

        // The point of the whole feature: version 1 still holds what version 1 held, even though
        // the live document no longer does.
        JsonNode v1 = read(authGet("/tenant/context/versions/1", token), HttpStatus.OK);
        assertThat(v1.get("version").get("version").asInt()).isEqualTo(1);
        assertThat(v1.get("document").get("companyName").asText()).isEqualTo("Alfa");
        assertThat(v1.get("document").get("competitors").get(0).asText()).isEqualTo("Beta");

        JsonNode live = read(authGet("/tenant/context", token), HttpStatus.OK);
        assertThat(live.get("companyName").asText()).isEqualTo("Alfa Tecnologia");

        // A version that was never written is a 404, not an empty body.
        assertThat(getStatus("/tenant/context/versions/9", token)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getStatus("/tenant/context/versions/0", token)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void savingTheSameDocumentAgainDoesNotAddAVersion() throws Exception {
        String token = signupAndLogin("hist-noop@nora.dev", "Alfa");
        String document =
                json(
                        Map.of(
                                "companyName",
                                "Alfa",
                                "products",
                                List.of(Map.of("name", "Radar", "keyDifferentiators", List.of()))));

        put("/tenant/context", document, token);
        put("/tenant/context", document, token);

        // Re-saving an unchanged form is the most common thing a user does to a settings page. One
        // row per click would bury the edits that matter under entries that differ in nothing.
        JsonNode history = read(authGet("/tenant/context/versions", token), HttpStatus.OK);
        assertThat(history.get("total").asInt()).isEqualTo(1);

        // ...and a real change still lands, so the guard is "unchanged", not "second save".
        put("/tenant/context", json(Map.of("companyName", "Alfa Digital")), token);
        JsonNode after = read(authGet("/tenant/context/versions", token), HttpStatus.OK);
        assertThat(after.get("total").asInt()).isEqualTo(2);
    }

    @Test
    void historyNeverCrossesTheTenantBoundary() throws Exception {
        String tokenA = signupAndLogin("hist-iso-a@nora.dev", "Alfa");
        String tokenB = signupAndLogin("hist-iso-b@nora.dev", "Beta");

        put("/tenant/context", json(Map.of("companyName", "Industria Alfa")), tokenA);
        put("/tenant/context", json(Map.of("companyName", "Industria Alfa SA")), tokenA);

        // B has two versions' worth of A's numbering available to guess and gets neither: an
        // empty history, and 404 on both version numbers that exist for A.
        JsonNode historyB = read(authGet("/tenant/context/versions", tokenB), HttpStatus.OK);
        assertThat(historyB.get("items").size()).isZero();
        assertThat(getStatus("/tenant/context/versions/1", tokenB)).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(getStatus("/tenant/context/versions/2", tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Positive control on the same two handlers: B's own writes produce B's own history,
        // numbered from 1 and holding B's document — the numbering is per context, not global.
        put("/tenant/context", json(Map.of("companyName", "Consultoria Beta")), tokenB);
        JsonNode ownHistory = read(authGet("/tenant/context/versions", tokenB), HttpStatus.OK);
        assertThat(ownHistory.get("total").asInt()).isEqualTo(1);
        JsonNode ownV1 = read(authGet("/tenant/context/versions/1", tokenB), HttpStatus.OK);
        assertThat(ownV1.get("document").get("companyName").asText()).isEqualTo("Consultoria Beta");

        // And A is untouched by any of it.
        JsonNode historyA = read(authGet("/tenant/context/versions", tokenA), HttpStatus.OK);
        assertThat(historyA.get("total").asInt()).isEqualTo(2);
        JsonNode aV1 = read(authGet("/tenant/context/versions/1", tokenA), HttpStatus.OK);
        assertThat(aV1.get("document").get("companyName").asText()).isEqualTo("Industria Alfa");
    }

    /* ============================== helpers ================================ */

    private ResponseEntity<String> put(String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        ResponseEntity<String> resp =
                rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return resp;
    }

    private HttpStatusCode getStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private String signupAndLogin(String email, String name) throws Exception {
        String payload = json(Map.of("email", email, "password", PASSWORD, "displayName", name));
        JsonNode signup = postJson("/auth/signup", payload, HttpStatus.CREATED);
        String verify = json(Map.of("token", signup.get("emailVerificationDevToken").asText()));
        postJson("/auth/verify-email", verify, HttpStatus.NO_CONTENT);
        String payloadLogin = json(Map.of("email", email, "password", PASSWORD));
        return postJson("/auth/login", payloadLogin, HttpStatus.OK).get("accessToken").asText();
    }

    private JsonNode postJson(String path, String body, HttpStatus expected) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp =
                rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
        return read(resp, expected);
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

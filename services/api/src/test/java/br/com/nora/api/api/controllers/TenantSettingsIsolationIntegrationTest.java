package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Cross-tenant isolation of the workspace settings and of the IAM user roster: {@code GET /tenant},
 * {@code PUT /tenant/name}, the two {@code /tenant/context} handlers and {@code GET /iam/invites}.
 * These were covered only for the caller's own tenant; nothing proved the boundary.
 *
 * <p>They are a different shape from the other aggregates: none of them takes a resource id in the
 * path, so there is no "other tenant's URL" to request. Everything they touch is selected by the
 * tenant claim inside the JWT, which means the property to prove is that the claim is the ONLY
 * thing that selects it — one tenant's write must land on its own row and leave the other's alone,
 * and one tenant's read must never surface the other's value.
 *
 * <p>Why it needs a test at all: row-level security is written but not enforced at runtime. The
 * application connects as the table owner and the enforce flag defaults to off, and {@code tenants}
 * is one of the thirteen tables migration V020 explicitly disabled RLS on, so the application's own
 * {@code tenant_id} scoping is the only control here.
 *
 * <p>The intruder is the Root of the other tenant, so the IAM gate cannot be what refuses: Root
 * short-circuits {@code AuthorizationService} before the policy evaluator runs, and the ARN the
 * interceptor checks ({@code nora:tenant/{caller}} and {@code nora:tenant/{caller}:tenant/context})
 * is built from the caller's own claim. This matters because since #407 authorization is
 * deny-by-default: a principal with no policies would be refused before scoping was ever consulted,
 * and the test would then pass with the scoping removed entirely.
 *
 * <p>The IAM user roster is exercised through the invitations, which is the tenant's member-
 * management surface — there is no {@code GET /iam/users} endpoint. The other user listing, {@code
 * GET /iam/groups/{id}/members}, is proven in {@code IamIsolationIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantSettingsIsolationIntegrationTest {

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

    /* ==================== GET /tenant + PUT /tenant/name =================== */

    @Test
    void workspaceSettings_areSelectedOnlyByTheCallersOwnTenantClaim() throws Exception {
        String tokenA = signupAndLogin("tenant-iso-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(tokenA, "tenantId");
        String tokenB = signupAndLogin("tenant-iso-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");

        // Each Root reads its own workspace, never the other's.
        JsonNode workspaceA = read(authGet("/tenant", tokenA), HttpStatus.OK);
        JsonNode workspaceB = read(authGet("/tenant", tokenB), HttpStatus.OK);
        assertThat(workspaceA.get("id").asText()).isEqualTo(tenantA.toString());
        assertThat(workspaceB.get("id").asText()).isEqualTo(tenantB.toString());
        String nameOfA = workspaceA.get("name").asText();

        // The identity endpoint agrees: B's principal is bound to B's tenant.
        JsonNode meB = read(authGet("/auth/me", tokenB), HttpStatus.OK);
        assertThat(meB.get("tenantId").asText()).isEqualTo(tenantB.toString());

        // B renames. The write is authorized (Root) and succeeds — on B's own row.
        String renameByB = json(Map.of("name", "Espaco renomeado pelo Beta"));
        JsonNode renamedB = read(put("/tenant/name", renameByB, tokenB), HttpStatus.OK);
        assertThat(renamedB.get("id").asText()).isEqualTo(tenantB.toString());
        assertThat(renamedB.get("name").asText()).isEqualTo("Espaco renomeado pelo Beta");

        // A's workspace was not touched by it.
        JsonNode afterB = read(authGet("/tenant", tokenA), HttpStatus.OK);
        assertThat(afterB.get("id").asText()).isEqualTo(tenantA.toString());
        assertThat(afterB.get("name").asText()).isEqualTo(nameOfA);

        // Positive control on the same endpoint: A renames its own, and B keeps its value.
        String renameByA = json(Map.of("name", "Espaco renomeado pelo Alfa"));
        JsonNode renamedA = read(put("/tenant/name", renameByA, tokenA), HttpStatus.OK);
        assertThat(renamedA.get("name").asText()).isEqualTo("Espaco renomeado pelo Alfa");
        JsonNode finalB = read(authGet("/tenant", tokenB), HttpStatus.OK);
        assertThat(finalB.get("name").asText()).isEqualTo("Espaco renomeado pelo Beta");
    }

    /* ========================== /tenant/context ============================ */

    @Test
    void tenantContext_isNeverSharedAcrossTenants() throws Exception {
        String tokenA = signupAndLogin("ctx-iso-a@nora.dev", "Alfa");
        UUID tenantA = readClaim(tokenA, "tenantId");
        String tokenB = signupAndLogin("ctx-iso-b@nora.dev", "Beta");
        UUID tenantB = readClaim(tokenB, "tenantId");

        String contextA = json(Map.of("companyName", "Industria Alfa"));
        JsonNode savedA = read(put("/tenant/context", contextA, tokenA), HttpStatus.OK);
        assertThat(savedA.get("tenantId").asText()).isEqualTo(tenantA.toString());

        // B has no context of its own, and A's does not stand in for it: 404, not A's payload.
        assertThat(getStatus("/tenant/context", tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Positive control: B writes and reads its own on the very same two handlers.
        String contextB = json(Map.of("companyName", "Consultoria Beta"));
        JsonNode savedB = read(put("/tenant/context", contextB, tokenB), HttpStatus.OK);
        assertThat(savedB.get("tenantId").asText()).isEqualTo(tenantB.toString());
        JsonNode readByB = read(authGet("/tenant/context", tokenB), HttpStatus.OK);
        assertThat(readByB.get("companyName").asText()).isEqualTo("Consultoria Beta");

        // B's upsert was an insert into B's row, not an overwrite of A's.
        JsonNode readByA = read(authGet("/tenant/context", tokenA), HttpStatus.OK);
        assertThat(readByA.get("companyName").asText()).isEqualTo("Industria Alfa");
    }

    /* ============================ /iam/invites ============================= */

    @Test
    void iamUserRoster_doesNotCrossTheTenantBoundary() throws Exception {
        String tokenA = signupAndLogin("roster-iso-a@nora.dev", "Alfa");
        String tokenB = signupAndLogin("roster-iso-b@nora.dev", "Beta");

        String guestOfA = "convidado-do-alfa@nora.dev";
        String guestOfB = "convidado-do-beta@nora.dev";
        String inviteA = invite(tokenA, guestOfA);
        String inviteB = invite(tokenB, guestOfB);

        // Each roster lists its own pending member and only its own.
        assertThat(invitedEmails(tokenA)).containsExactly(guestOfA);
        assertThat(invitedEmails(tokenB)).containsExactly(guestOfB);

        // Revoking across the boundary is refused with 404, never 403.
        assertThat(delete("/iam/invites/" + inviteA, tokenB)).isEqualTo(HttpStatus.NOT_FOUND);

        // Refused means untouched: A's invite is still pending.
        assertThat(inviteStatus(tokenA, inviteA)).isEqualTo("PENDING");

        // Positive control: B revokes its own invite on the same handler.
        assertThat(delete("/iam/invites/" + inviteB, tokenB)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(inviteStatus(tokenB, inviteB)).isEqualTo("REVOKED");
    }

    /* ============================== helpers ================================ */

    private String invite(String token, String email) throws Exception {
        String body = json(Map.of("email", email));
        ResponseEntity<String> resp = exchange(HttpMethod.POST, "/iam/users/invite", body, token);
        return read(resp, HttpStatus.CREATED).get("id").asText();
    }

    private List<String> invitedEmails(String token) throws Exception {
        JsonNode listing = read(authGet("/iam/invites", token), HttpStatus.OK);
        List<String> emails = new ArrayList<>();
        listing.get("items").forEach(item -> emails.add(item.get("email").asText()));
        return emails;
    }

    private String inviteStatus(String token, String inviteId) throws Exception {
        JsonNode listing = read(authGet("/iam/invites", token), HttpStatus.OK);
        for (JsonNode item : listing.get("items")) {
            if (item.get("id").asText().equals(inviteId)) {
                return item.get("status").asText();
            }
        }
        return null;
    }

    private HttpStatusCode getStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private ResponseEntity<String> put(String path, String body, String token) {
        return exchange(HttpMethod.PUT, path, body, token);
    }

    private HttpStatusCode delete(String path, String token) {
        return exchange(HttpMethod.DELETE, path, null, token).getStatusCode();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> exchange(
            HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, String.class);
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

    private UUID readClaim(String jwt, String claim) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        String payload = new String(decoded, StandardCharsets.UTF_8);
        return UUID.fromString(mapper.readTree(payload).get(claim).asText());
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

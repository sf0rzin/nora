package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobre US43 (IAM Policy Simulator): POST /iam/policies/simulate avalia se um usuario do tenant
 * pode executar action+resource. Cenarios:
 *
 * <ul>
 *   <li>Membro com policy Allow meeting:read recebe {@code allowed=true} (PERMITIDO).
 *   <li>Mesmo membro, sem policy para meeting:delete, recebe {@code allowed=false} (NEGADO).
 * </ul>
 *
 * Reusa {@code AuthorizationService} (mesma logica do enforcement real), garantindo paridade entre
 * o simulador e o comportamento de producao.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamPolicySimulatorIntegrationTest {

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
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void simulate_returnsAllowedForGrantedActionAndDeniedForUngrantedAction() throws Exception {
        // 1. Root (primeiro usuario do tenant e auto-promovido a Root via V006).
        String rootToken = signupAndLogin("root-sim@nora.dev", "SenhaForte123", "Root Sim");
        UUID tenantId = readClaim(rootToken, "tenantId");

        // 2. Membro nao-Root no mesmo tenant.
        UUID memberUserId =
                insertActiveMember(tenantId, "member-sim@nora.dev", "SenhaForte123", "Member Sim");

        // 3. Root cria policy: Allow meeting:read em qualquer meeting do tenant; atribui ao membro.
        String policyDoc =
                "{"
                        + "\"version\":\"2026-05-07\","
                        + "\"statements\":[{"
                        + "\"effect\":\"Allow\","
                        + "\"action\":[\"meeting:read\"],"
                        + "\"resource\":[\"nora:tenant/"
                        + tenantId
                        + ":meeting/*\"]"
                        + "}]}";
        String policyId = createPolicy(rootToken, "AllowReadMeetings", policyDoc);
        attachPolicyToUser(rootToken, memberUserId, policyId);

        String resource = "nora:tenant/" + tenantId + ":meeting/abc";

        // 4. Simula action concedida → PERMITIDO.
        JsonNode allow =
                simulate(rootToken, memberUserId, "meeting:read", resource).body(HttpStatus.OK);
        assertThat(allow.get("userId").asText()).isEqualTo(memberUserId.toString());
        assertThat(allow.get("action").asText()).isEqualTo("meeting:read");
        assertThat(allow.get("resource").asText()).isEqualTo(resource);
        assertThat(allow.get("allowed").asBoolean()).isTrue();

        // 5. Simula action NAO concedida → NEGADO.
        JsonNode deny =
                simulate(rootToken, memberUserId, "meeting:delete", resource).body(HttpStatus.OK);
        assertThat(deny.get("allowed").asBoolean()).isFalse();
    }

    // ────────── helpers ──────────

    private RequestExec simulate(String token, UUID userId, String action, String resource)
            throws Exception {
        return postJsonAuth(
                "/iam/policies/simulate",
                Map.of("userId", userId.toString(), "action", action, "resource", resource),
                token);
    }

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name))
                        .body(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken)).expect(HttpStatus.NO_CONTENT);
        return login(email, pwd);
    }

    private String login(String email, String pwd) throws Exception {
        JsonNode body =
                postJson("/auth/login", Map.of("email", email, "password", pwd))
                        .body(HttpStatus.OK);
        return body.get("accessToken").asText();
    }

    /** Insere usuario ACTIVE, e-mail verificado, nao-Root no tenant indicado. */
    private UUID insertActiveMember(UUID tenantId, String email, String pwd, String displayName) {
        UUID userId = UUID.randomUUID();
        String hash = passwordEncoder.encode(pwd);
        jdbc.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, display_name, status,"
                        + " is_root, email_verified_at, created_at, updated_at) VALUES (?, ?, ?, ?,"
                        + " ?, 'ACTIVE', FALSE, NOW(), NOW(), NOW())",
                userId,
                tenantId,
                email,
                hash,
                displayName);
        return userId;
    }

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        JsonNode docNode = mapper.readTree(documentJson);
        JsonNode body =
                postJsonAuth("/iam/policies", Map.of("name", name, "document", docNode), token)
                        .body(HttpStatus.CREATED);
        return body.get("id").asText();
    }

    private void attachPolicyToUser(String token, UUID userId, String policyId) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> resp =
                rest.exchange(
                        "/iam/users/" + userId + "/policies/" + policyId,
                        HttpMethod.POST,
                        new HttpEntity<>(h),
                        String.class);
        assertThat(resp.getStatusCode())
                .as("attach body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private UUID readClaim(String jwt, String claim) throws Exception {
        String[] parts = jwt.split("\\.");
        String payload =
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString(mapper.readTree(payload).get(claim).asText());
    }

    private RequestExec postJson(String path, Object body) throws Exception {
        return postJsonAuth(path, body, null);
    }

    private RequestExec postJsonAuth(String path, Object body, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private final class RequestExec {
        final ResponseEntity<String> response;

        RequestExec(ResponseEntity<String> r) {
            this.response = r;
        }

        void expect(HttpStatus expected) {
            assertThat(response.getStatusCode())
                    .as("body=%s", response.getBody())
                    .isEqualTo(expected);
        }

        JsonNode body(HttpStatus expected) throws Exception {
            expect(expected);
            return response.getBody() == null
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.getBody());
        }
    }
}

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
import org.springframework.http.HttpStatusCode;
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
 * Cobre US32 (corporate domain restriction). Cenarios:
 *
 * <ul>
 *   <li>Root atualiza o dominio com sucesso (200) e grava audit event.
 *   <li>Membro nao-Root sem policy recebe 403.
 *   <li>Dominio invalido devolve 422 (formato sintaticamente correto mas semanticamente rejeitado):
 *       {@code @invalid}, {@code acme}, {@code " "}.
 *   <li>GET /tenant/domain reflete o estado atual.
 *   <li>Membro com policy {@code tenant:domain:read} consegue ler; {@code tenant:domain:write}
 *       consegue escrever.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TenantDomainIntegrationTest {

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
    void rootUpdatesDomain_persists_andAuditsEvent() throws Exception {
        String rootEmail = "root-domain@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Domain");
        UUID tenantId = readClaim(rootToken, "tenantId");

        // Estado inicial: dominio nulo.
        ResponseEntity<String> initialGet = authGet("/tenant/domain", rootToken);
        assertThat(initialGet.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode initialBody = mapper.readTree(initialGet.getBody());
        assertThat(initialBody.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(initialBody.get("allowedEmailDomain").isNull()).isTrue();

        // Update com dominio valido (e em MAIUSCULAS pra checar normalizacao).
        ResponseEntity<String> putResp =
                putJsonAuth("/tenant/domain", Map.of("allowedEmailDomain", "ACME.com"), rootToken);
        assertThat(putResp.getStatusCode())
                .as("body=%s", putResp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode putBody = mapper.readTree(putResp.getBody());
        assertThat(putBody.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(putBody.get("allowedEmailDomain").asText()).isEqualTo("acme.com");
        assertThat(putBody.get("updatedAt").asText()).isNotBlank();
        assertThat(putBody.get("updatedBy").asText()).isNotBlank();

        // GET reflete o novo estado.
        JsonNode afterGet = mapper.readTree(authGet("/tenant/domain", rootToken).getBody());
        assertThat(afterGet.get("allowedEmailDomain").asText()).isEqualTo("acme.com");

        // Audit event gravado.
        Integer auditCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_audit_events "
                                + "WHERE tenant_id = ? AND action = 'tenant.domain.updated'",
                        Integer.class,
                        tenantId);
        assertThat(auditCount).isEqualTo(1);

        // Coluna persistida.
        String persisted =
                jdbc.queryForObject(
                        "SELECT allowed_email_domain FROM tenants WHERE id = ?",
                        String.class,
                        tenantId);
        assertThat(persisted).isEqualTo("acme.com");

        // Clear (null) tambem deve ser aceito.
        ResponseEntity<String> clearResp =
                putJsonRaw("/tenant/domain", "{\"allowedEmailDomain\":null}", rootToken);
        assertThat(clearResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode clearBody = mapper.readTree(clearResp.getBody());
        assertThat(clearBody.get("allowedEmailDomain").isNull()).isTrue();
    }

    @Test
    void nonRootMemberWithoutPolicy_isForbidden_onReadAndWrite() throws Exception {
        String rootEmail = "root-domain-fb@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root FB");
        UUID tenantId = readClaim(rootToken, "tenantId");

        String memberEmail = "member-domain-fb@nora.dev";
        insertActiveMember(tenantId, memberEmail, "SenhaForte123", "Member FB");
        String memberToken = login(memberEmail, "SenhaForte123");

        assertThat(authGetStatus("/tenant/domain", memberToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(
                        putJsonAuth(
                                        "/tenant/domain",
                                        Map.of("allowedEmailDomain", "acme.com"),
                                        memberToken)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberWithReadPolicy_canRead_butCannotWrite() throws Exception {
        String rootEmail = "root-domain-policy@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Pol");
        UUID tenantId = readClaim(rootToken, "tenantId");

        String memberEmail = "member-domain-policy@nora.dev";
        UUID memberUserId =
                insertActiveMember(tenantId, memberEmail, "SenhaForte123", "Member Pol");
        String memberToken = login(memberEmail, "SenhaForte123");

        // Anexa policy de read-only.
        String readDoc =
                "{"
                        + "\"version\":\"2026-05-07\","
                        + "\"statements\":[{"
                        + "\"effect\":\"Allow\","
                        + "\"action\":[\"tenant:domain:read\"],"
                        + "\"resource\":[\"nora:tenant/"
                        + tenantId
                        + "\"]"
                        + "}]}";
        String policyId = createPolicy(rootToken, "ReadTenantDomain", readDoc);
        attachPolicyToUser(rootToken, memberUserId, policyId);

        // Pode ler.
        assertThat(authGetStatus("/tenant/domain", memberToken)).isEqualTo(HttpStatus.OK);

        // Mas nao escrever.
        assertThat(
                        putJsonAuth(
                                        "/tenant/domain",
                                        Map.of("allowedEmailDomain", "acme.com"),
                                        memberToken)
                                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void memberWithWritePolicy_canUpdate_andEmitsAudit() throws Exception {
        String rootEmail = "root-domain-write@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root W");
        UUID tenantId = readClaim(rootToken, "tenantId");

        String memberEmail = "member-domain-write@nora.dev";
        UUID memberUserId = insertActiveMember(tenantId, memberEmail, "SenhaForte123", "Member W");
        String memberToken = login(memberEmail, "SenhaForte123");

        String writeDoc =
                "{"
                        + "\"version\":\"2026-05-07\","
                        + "\"statements\":[{"
                        + "\"effect\":\"Allow\","
                        + "\"action\":[\"tenant:domain:write\",\"tenant:domain:read\"],"
                        + "\"resource\":[\"nora:tenant/"
                        + tenantId
                        + "\"]"
                        + "}]}";
        String policyId = createPolicy(rootToken, "WriteTenantDomain", writeDoc);
        attachPolicyToUser(rootToken, memberUserId, policyId);

        ResponseEntity<String> putResp =
                putJsonAuth(
                        "/tenant/domain", Map.of("allowedEmailDomain", "memberco.io"), memberToken);
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Audit registra o membro como actor.
        UUID auditActor =
                jdbc.queryForObject(
                        "SELECT actor_user_id FROM iam_audit_events "
                                + "WHERE tenant_id = ? AND action = 'tenant.domain.updated' "
                                + "ORDER BY created_at DESC LIMIT 1",
                        UUID.class,
                        tenantId);
        assertThat(auditActor).isEqualTo(memberUserId);
    }

    @Test
    void invalidDomain_returns422_andStableErrorCode() throws Exception {
        String rootEmail = "root-domain-invalid@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Inv");

        // @invalid: nao pode comecar com @.
        ResponseEntity<String> r1 =
                putJsonAuth("/tenant/domain", Map.of("allowedEmailDomain", "@invalid"), rootToken);
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(r1.getBody()).get("code").asText())
                .isEqualTo("TENANT_DOMAIN_INVALID");

        // acme: sem TLD.
        ResponseEntity<String> r2 =
                putJsonAuth("/tenant/domain", Map.of("allowedEmailDomain", "acme"), rootToken);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // "   ": vazio apos trim trata como clear, retorna 200 com null.
        ResponseEntity<String> r3 =
                putJsonAuth("/tenant/domain", Map.of("allowedEmailDomain", "   "), rootToken);
        assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(r3.getBody()).get("allowedEmailDomain").isNull()).isTrue();

        // dominio com caracter invalido.
        ResponseEntity<String> r4 =
                putJsonAuth(
                        "/tenant/domain", Map.of("allowedEmailDomain", "acme com.br"), rootToken);
        assertThat(r4.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // dominio com underscore.
        ResponseEntity<String> r5 =
                putJsonAuth(
                        "/tenant/domain", Map.of("allowedEmailDomain", "acme_corp.com"), rootToken);
        assertThat(r5.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ────────── helpers (copiados de IamScopingIntegrationTest para mantermos isolamento)
    // ──────────

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

    private HttpStatusCode authGetStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
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

    private ResponseEntity<String> putJsonAuth(String path, Object body, String token)
            throws Exception {
        return putJsonRaw(path, mapper.writeValueAsString(body), token);
    }

    private ResponseEntity<String> putJsonRaw(String path, String rawJson, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(
                path, HttpMethod.PUT, new HttpEntity<>(rawJson, headers), String.class);
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

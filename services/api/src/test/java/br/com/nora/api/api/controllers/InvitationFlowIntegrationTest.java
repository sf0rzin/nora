package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import br.com.nora.api.application.ports.EmailSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers US06 end-to-end (ADR 0011): invitation, acceptance, listing and revocation. Scenarios:
 *
 * <ul>
 *   <li>Root invites, email goes to the queue, token accepts, user created + JWT + groups attached.
 *   <li>Domain mismatch when the tenant has {@code allowedEmailDomain} configured → 422.
 *   <li>Non-Root without IAM permission → 403.
 *   <li>Nonexistent token → 404.
 *   <li>Expired token → 410 and status persists as EXPIRED.
 *   <li>Repeated acceptance → 409.
 *   <li>Revocation by Root → REVOKED + audit.
 *   <li>List filtered by status.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class InvitationFlowIntegrationTest {

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
    @MockitoBean EmailSender emailSender;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void happyPath_rootInvites_accepts_userCreated_jwtIssued_groupsAttached() throws Exception {
        String rootEmail = uniq("root");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Invite");
        UUID tenantId = readClaim(rootToken, "tenantId");

        // Create a group.
        String groupId = createGroup(rootToken, "Vendas " + UUID.randomUUID());

        String inviteEmail = uniq("carlos");
        ResponseEntity<String> inviteResp =
                postJsonAuth(
                                "/iam/users/invite",
                                Map.of(
                                        "email",
                                        inviteEmail,
                                        "groupIds",
                                        List.of(groupId),
                                        "expiresInDays",
                                        7),
                                rootToken)
                        .response;
        assertThat(inviteResp.getStatusCode())
                .as("body=%s", inviteResp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode invBody = mapper.readTree(inviteResp.getBody());
        assertThat(invBody.get("status").asText()).isEqualTo("PENDING");
        assertThat(invBody.has("token")).as("token must not be exposed in the response").isFalse();
        UUID inviteId = UUID.fromString(invBody.get("id").asText());

        // Raw token captured from the acceptUrl the backend sent to the email (the DB only has the
        // hash).
        String token = captureInviteToken(inviteEmail);
        assertThat(token).isNotBlank();

        // Audit invited.
        Integer auditCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_audit_events WHERE tenant_id = ? AND action ="
                                + " 'iam.user.invited'",
                        Integer.class,
                        tenantId);
        assertThat(auditCount).isEqualTo(1);

        // Acceptance (PUBLIC, no auth).
        ResponseEntity<String> acceptResp =
                postJson(
                                "/iam/invites/" + token + "/accept",
                                Map.of("displayName", "Carlos Silva", "password", "SenhaForte123"))
                        .response;
        assertThat(acceptResp.getStatusCode())
                .as("body=%s", acceptResp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode accBody = mapper.readTree(acceptResp.getBody());
        assertThat(accBody.get("accessToken").asText()).isNotBlank();
        assertThat(accBody.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(accBody.get("email").asText()).isEqualTo(inviteEmail);
        UUID acceptedUserId = UUID.fromString(accBody.get("userId").asText());

        // User created.
        Integer userCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE id = ? AND tenant_id = ?",
                        Integer.class,
                        acceptedUserId,
                        tenantId);
        assertThat(userCount).isEqualTo(1);

        // Group attachment applied.
        Integer memberCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_user_groups WHERE user_id = ? AND group_id = ?",
                        Integer.class,
                        acceptedUserId,
                        UUID.fromString(groupId));
        assertThat(memberCount).isEqualTo(1);

        // Invite became ACCEPTED.
        String status =
                jdbc.queryForObject(
                        "SELECT status FROM iam_user_invitations WHERE id = ?",
                        String.class,
                        inviteId);
        assertThat(status).isEqualTo("ACCEPTED");

        // Audit accepted.
        Integer acceptedAudits =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_audit_events WHERE tenant_id = ? AND action ="
                                + " 'iam.invite.accepted'",
                        Integer.class,
                        tenantId);
        assertThat(acceptedAudits).isEqualTo(1);
    }

    @Test
    void invite_rejects_whenDomainDoesNotMatchTenantAllowedDomain() throws Exception {
        String rootEmail = uniq("root-domain");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Dom");
        UUID tenantId = readClaim(rootToken, "tenantId");

        // Configure the tenant's domain.
        ResponseEntity<String> putDomain =
                putJsonAuth("/tenant/domain", Map.of("allowedEmailDomain", "acme.com"), rootToken);
        assertThat(putDomain.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> inviteResp =
                postJsonAuth("/iam/users/invite", Map.of("email", "alex@gmail.com"), rootToken)
                        .response;
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        JsonNode body = mapper.readTree(inviteResp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED");

        // Invite audit was NOT recorded.
        Integer auditCount =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_audit_events WHERE tenant_id = ? AND action ="
                                + " 'iam.user.invited'",
                        Integer.class,
                        tenantId);
        assertThat(auditCount).isEqualTo(0);
    }

    @Test
    void nonRoot_withoutInvitePermission_isForbidden() throws Exception {
        String rootEmail = uniq("root-fb");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root FB");
        UUID tenantId = readClaim(rootToken, "tenantId");

        String memberEmail = uniq("member-fb");
        insertActiveMember(tenantId, memberEmail, "SenhaForte123", "Member FB");
        String memberToken = login(memberEmail, "SenhaForte123");

        ResponseEntity<String> resp =
                postJsonAuth("/iam/users/invite", Map.of("email", "outsider@x.com"), memberToken)
                        .response;
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(authGet("/iam/invites", memberToken).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void accept_unknownToken_returns404() throws Exception {
        ResponseEntity<String> resp =
                postJson(
                                "/iam/invites/does-not-exist/accept",
                                Map.of("displayName", "X", "password", "SenhaForte123"))
                        .response;
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(mapper.readTree(resp.getBody()).get("code").asText())
                .isEqualTo("INVITE_NOT_FOUND");
    }

    @Test
    void accept_expiredToken_returns410_andStatusBecomesExpired() throws Exception {
        String rootEmail = uniq("root-exp");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Exp");

        String inviteEmail = uniq("alex");
        ResponseEntity<String> inviteResp =
                postJsonAuth(
                                "/iam/users/invite",
                                Map.of("email", inviteEmail, "expiresInDays", 1),
                                rootToken)
                        .response;
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID inviteId = UUID.fromString(mapper.readTree(inviteResp.getBody()).get("id").asText());

        // Force expires_at into the past directly via JDBC.
        jdbc.update(
                "UPDATE iam_user_invitations SET expires_at = NOW() - INTERVAL '1 hour' WHERE id"
                        + " = ?",
                inviteId);
        String token = captureInviteToken(inviteEmail);

        ResponseEntity<String> acceptResp =
                postJson(
                                "/iam/invites/" + token + "/accept",
                                Map.of("displayName", "Alex", "password", "SenhaForte123"))
                        .response;
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(mapper.readTree(acceptResp.getBody()).get("code").asText())
                .isEqualTo("INVITE_EXPIRED");

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM iam_user_invitations WHERE id = ?",
                        String.class,
                        inviteId);
        assertThat(status).isEqualTo("EXPIRED");
    }

    @Test
    void accept_alreadyAccepted_returns409() throws Exception {
        String rootEmail = uniq("root-dup");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Dup");

        String inviteEmail = uniq("dup");
        postJsonAuth("/iam/users/invite", Map.of("email", inviteEmail), rootToken)
                .expect(HttpStatus.CREATED);
        String token = captureInviteToken(inviteEmail);

        ResponseEntity<String> first =
                postJson(
                                "/iam/invites/" + token + "/accept",
                                Map.of("displayName", "Dup", "password", "SenhaForte123"))
                        .response;
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second =
                postJson(
                                "/iam/invites/" + token + "/accept",
                                Map.of("displayName", "Dup2", "password", "SenhaForte123"))
                        .response;
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mapper.readTree(second.getBody()).get("code").asText())
                .isEqualTo("INVITE_ALREADY_ACCEPTED");
    }

    @Test
    void revoke_byRoot_marksRevoked_andAudits() throws Exception {
        String rootEmail = uniq("root-rev");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Rev");
        UUID tenantId = readClaim(rootToken, "tenantId");

        ResponseEntity<String> inviteResp =
                postJsonAuth("/iam/users/invite", Map.of("email", uniq("revoke")), rootToken)
                        .response;
        UUID inviteId = UUID.fromString(mapper.readTree(inviteResp.getBody()).get("id").asText());

        ResponseEntity<String> rev =
                rest.exchange(
                        "/iam/invites/" + inviteId,
                        HttpMethod.DELETE,
                        new HttpEntity<>(bearer(rootToken)),
                        String.class);
        assertThat(rev.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String status =
                jdbc.queryForObject(
                        "SELECT status FROM iam_user_invitations WHERE id = ?",
                        String.class,
                        inviteId);
        assertThat(status).isEqualTo("REVOKED");

        Integer audits =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM iam_audit_events WHERE tenant_id = ? AND action ="
                                + " 'iam.invite.revoked'",
                        Integer.class,
                        tenantId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void list_filtersByStatus() throws Exception {
        String rootEmail = uniq("root-list");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root List");

        // 2 PENDING + 1 will be revoked.
        String a =
                inviteIdFromResponse(
                        postJsonAuth("/iam/users/invite", Map.of("email", uniq("a")), rootToken)
                                .response);
        String b =
                inviteIdFromResponse(
                        postJsonAuth("/iam/users/invite", Map.of("email", uniq("b")), rootToken)
                                .response);

        rest.exchange(
                "/iam/invites/" + b,
                HttpMethod.DELETE,
                new HttpEntity<>(bearer(rootToken)),
                String.class);

        ResponseEntity<String> pendingList = authGet("/iam/invites?status=PENDING", rootToken);
        assertThat(pendingList.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode pendingBody = mapper.readTree(pendingList.getBody());
        assertThat(pendingBody.get("items"))
                .anySatisfy(node -> assertThat(node.get("id").asText()).isEqualTo(a));
        // Ensure B (revoked) does not show up under PENDING.
        for (JsonNode item : pendingBody.get("items")) {
            assertThat(item.get("status").asText()).isEqualTo("PENDING");
        }

        ResponseEntity<String> revokedList = authGet("/iam/invites?status=REVOKED", rootToken);
        JsonNode revokedBody = mapper.readTree(revokedList.getBody());
        boolean foundB = false;
        for (JsonNode item : revokedBody.get("items")) {
            if (item.get("id").asText().equals(b)) {
                foundB = true;
            }
        }
        assertThat(foundB).isTrue();
    }

    @Test
    void invite_response_doesNotLeakToken() throws Exception {
        String rootEmail = uniq("root-leak");
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root Leak");

        ResponseEntity<String> resp =
                postJsonAuth("/iam/users/invite", Map.of("email", uniq("noleak")), rootToken)
                        .response;
        assertThat(resp.getBody()).doesNotContain("token");
    }

    // ────────── helpers ──────────

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@nora.dev";
    }

    private String inviteIdFromResponse(ResponseEntity<String> resp) throws Exception {
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    /**
     * Recovers the invitation's raw token from the {@code acceptUrl} the backend passed to the
     * EmailSender — the same channel as the real email. The DB stores only the SHA-256 ({@code
     * token_hash}), so reading the raw token via JDBC is no longer possible (by design — US06
     * hardening).
     */
    private String captureInviteToken(String inviteEmail) {
        ArgumentCaptor<String> acceptUrl = ArgumentCaptor.forClass(String.class);
        verify(emailSender)
                .sendInvitation(
                        eq(inviteEmail), anyString(), anyString(), acceptUrl.capture(), anyInt());
        String url = acceptUrl.getValue();
        return url.substring(url.lastIndexOf('/') + 1);
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

    private String createGroup(String token, String name) throws Exception {
        JsonNode body =
                postJsonAuth("/iam/groups", Map.of("name", name), token).body(HttpStatus.CREATED);
        return body.get("id").asText();
    }

    private HttpStatusCode authGetStatus(String path, String token) {
        return authGet(path, token).getStatusCode();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(
                path,
                HttpMethod.PUT,
                new HttpEntity<>(mapper.writeValueAsString(body), headers),
                String.class);
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

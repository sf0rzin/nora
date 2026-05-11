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
import org.springframework.core.io.ByteArrayResource;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cobre US19: visibilidade de meetings e tasks controlada por IAM policies. Cenarios:
 *
 * <ul>
 *   <li>Root acessa qualquer meeting (bypass).
 *   <li>Membro nao-Root sem policies recebe 403 em GET /meetings, GET /meetings/{id} e /tasks.
 *   <li>Membro com policy condicional (StringEquals em attributes) acessa apenas meetings que
 *       satisfazem a condition.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class IamScopingIntegrationTest {

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
    void rootBypassesIam_memberWithoutPolicyIsForbidden_memberWithConditionPolicySeesOnlyMatching()
            throws Exception {
        // 1. Root: signup auto-promove o primeiro usuario do tenant a Root (V006).
        String rootEmail = "root-iam@nora.dev";
        String rootToken = signupAndLogin(rootEmail, "SenhaForte123", "Root IAM");
        UUID tenantId = readClaim(rootToken, "tenantId");

        // 2. Root faz upload de duas reunioes com attributes diferentes.
        String meetingVendas =
                uploadMeeting(
                        rootToken,
                        "Discovery Acme",
                        Map.of("department", "Vendas"),
                        "Lucas: vamos fechar.\nMarina: ok.");
        String meetingSuporte =
                uploadMeeting(
                        rootToken,
                        "Ticket Acme",
                        Map.of("department", "Suporte"),
                        "Carlos: ticket aberto.\nAna: vou ver.");

        // Root acessa ambos via bypass.
        assertThat(authGetStatus("/meetings/" + meetingVendas, rootToken))
                .isEqualTo(HttpStatus.OK);
        assertThat(authGetStatus("/meetings/" + meetingSuporte, rootToken))
                .isEqualTo(HttpStatus.OK);

        // 3. Cria um membro nao-Root no mesmo tenant (workaround: convite eh pos-MVP).
        String memberEmail = "member-iam@nora.dev";
        UUID memberUserId = insertActiveMember(tenantId, memberEmail, "SenhaForte123", "Member");
        String memberToken = login(memberEmail, "SenhaForte123");

        // 4. Sem policies → 403 em qualquer endpoint protegido.
        assertThat(authGetStatus("/meetings", memberToken)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(authGetStatus("/meetings/" + meetingVendas, memberToken))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(authGetStatus("/tasks", memberToken)).isEqualTo(HttpStatus.FORBIDDEN);

        // 5. Root cria policy: Allow meeting:read SE department=Vendas; e atribui ao membro.
        String policyDoc =
                "{"
                        + "\"version\":\"2026-05-07\","
                        + "\"statements\":[{"
                        + "\"effect\":\"Allow\","
                        + "\"action\":[\"meeting:read\"],"
                        + "\"resource\":[\"nora:tenant/"
                        + tenantId
                        + ":meeting/*\"],"
                        + "\"condition\":{\"StringEquals\":{\"department\":\"Vendas\"}}"
                        + "}]}";
        String policyId = createPolicy(rootToken, "AllowVendasReadMeetings", policyDoc);
        attachPolicyToUser(rootToken, memberUserId, policyId);

        // 6. Membro agora le a reuniao de Vendas (condition satisfeita)…
        assertThat(authGetStatus("/meetings/" + meetingVendas, memberToken))
                .isEqualTo(HttpStatus.OK);

        // …mas continua bloqueado na de Suporte (condition falha).
        assertThat(authGetStatus("/meetings/" + meetingSuporte, memberToken))
                .isEqualTo(HttpStatus.FORBIDDEN);

        // 7. GET /meetings agora passa o pre-check (ha pelo menos uma allow possivel) e devolve
        // apenas a reuniao de Vendas no array de items.
        ResponseEntity<String> listResp = authGet("/meetings", memberToken);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode listBody = mapper.readTree(listResp.getBody());
        assertThat(listBody.get("items")).hasSize(1);
        assertThat(listBody.get("items").get(0).get("id").asText()).isEqualTo(meetingVendas);
    }

    // ────────── helpers ──────────

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson("/auth/signup", Map.of("email", email, "password", pwd, "displayName", name))
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

    private String uploadMeeting(
            String token, String title, Map<String, String> attributes, String content)
            throws Exception {
        String metadata =
                mapper.writeValueAsString(
                        Map.of(
                                "title",
                                title,
                                "transcriptFormat",
                                "TXT",
                                "attributes",
                                attributes));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        HttpHeaders metaH = new HttpHeaders();
        metaH.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(metadata, metaH));

        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource fileResource =
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "transcript.txt";
                    }
                };
        body.add("file", new HttpEntity<>(fileResource, fileH));

        ResponseEntity<String> resp =
                rest.postForEntity("/meetings", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("upload body=%s", resp.getBody())
                .isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    private String createPolicy(String token, String name, String documentJson) throws Exception {
        JsonNode docNode = mapper.readTree(documentJson);
        JsonNode body =
                postJsonAuth(
                                "/iam/policies",
                                Map.of("name", name, "document", docNode),
                                token)
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
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
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

package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fluxo end-to-end de project tracking: create -> list -> assign meeting -> detail mostra projeto
 * -> unassign. Mais checagem de isolamento por tenant (project de outro tenant => 404).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ProjectFlowIntegrationTest {

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

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void createListAssign_flowIsTenantScoped() throws Exception {
        String token = signupAndLogin("proj+owner@nora.dev", "SenhaForte123", "Owner Proj");

        // Create project
        JsonNode created =
                exec(
                                "/projects",
                                HttpMethod.POST,
                                token,
                                Map.of("name", "Projeto Alpha", "description", "Conta estrategica"))
                        .read(HttpStatus.CREATED);
        String projectId = created.get("id").asText();
        assertThat(created.get("name").asText()).isEqualTo("Projeto Alpha");
        assertThat(created.get("status").asText()).isEqualTo("ACTIVE");

        // List returns the project
        JsonNode list = authGet("/projects", token).read(HttpStatus.OK);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(projectId);

        // Rename + archive via PATCH
        JsonNode patched =
                exec(
                                "/projects/" + projectId,
                                HttpMethod.PATCH,
                                token,
                                Map.of("name", "Projeto Beta", "status", "ARCHIVED"))
                        .read(HttpStatus.OK);
        assertThat(patched.get("name").asText()).isEqualTo("Projeto Beta");
        assertThat(patched.get("status").asText()).isEqualTo("ARCHIVED");

        // Upload a meeting then assign it to the project
        String meetingId = uploadMeeting(token, "Discovery Acme");
        JsonNode assigned =
                exec(
                                "/meetings/" + meetingId + "/project",
                                HttpMethod.PUT,
                                token,
                                Map.of("projectId", projectId))
                        .read(HttpStatus.OK);
        assertThat(assigned.get("id").asText()).isEqualTo(projectId);

        // Meeting detail now exposes projectId + projectName
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.get("projectId").asText()).isEqualTo(projectId);
        assertThat(detail.get("projectName").asText()).isEqualTo("Projeto Beta");

        // Unassign (projectId = null) => 204 and detail no longer has a project
        ResponseEntity<String> unassign =
                execRaw(
                        "/meetings/" + meetingId + "/project",
                        HttpMethod.PUT,
                        token,
                        mapper.writeValueAsString(
                                java.util.Collections.singletonMap("projectId", null)));
        assertThat(unassign.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode detail2 = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail2.hasNonNull("projectId")).isFalse();

        // Tenant isolation: another tenant cannot see or touch this project.
        String otherToken = signupAndLogin("proj+other@nora.dev", "SenhaForte123", "Other");
        JsonNode otherList = authGet("/projects", otherToken).read(HttpStatus.OK);
        assertThat(otherList.get("items")).isEmpty();
        ResponseEntity<String> crossPatch =
                execRaw(
                        "/projects/" + projectId,
                        HttpMethod.PATCH,
                        otherToken,
                        mapper.writeValueAsString(Map.of("name", "Hijack")));
        assertThat(crossPatch.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void assignRejectsProjectFromAnotherTenant() throws Exception {
        // Tenant A owns the project; tenant B owns the meeting. B trying to assign A's project =>
        // 404.
        String tokenA = signupAndLogin("proj+a@nora.dev", "SenhaForte123", "A");
        JsonNode projA =
                exec("/projects", HttpMethod.POST, tokenA, Map.of("name", "Alpha A"))
                        .read(HttpStatus.CREATED);
        String projectAId = projA.get("id").asText();

        String tokenB = signupAndLogin("proj+b@nora.dev", "SenhaForte123", "B");
        String meetingBId = uploadMeeting(tokenB, "Reuniao B");
        ResponseEntity<String> cross =
                execRaw(
                        "/meetings/" + meetingBId + "/project",
                        HttpMethod.PUT,
                        tokenB,
                        mapper.writeValueAsString(Map.of("projectId", projectAId)));
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createRequiresAuthentication() {
        ResponseEntity<String> resp =
                rest.postForEntity("/projects", new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                exec(
                                "/auth/signup",
                                HttpMethod.POST,
                                null,
                                Map.of("email", email, "password", pwd, "displayName", name))
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        exec("/auth/verify-email", HttpMethod.POST, null, Map.of("token", verifyToken))
                .read(HttpStatus.NO_CONTENT);
        JsonNode login =
                exec("/auth/login", HttpMethod.POST, null, Map.of("email", email, "password", pwd))
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private String uploadMeeting(String token, String title) throws Exception {
        String metadata =
                mapper.writeValueAsString(
                        Map.of("title", title, "language", "pt-BR", "transcriptFormat", "TXT"));
        String content = "Lucas: bom dia.\nMarina: bom dia.";
        ResponseEntity<String> resp =
                multipartUpload(
                        "/meetings", token, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    private ResponseEntity<String> multipartUpload(
            String path, String token, String metadataJson, byte[] fileBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaH = new HttpHeaders();
        metaH.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(metadataJson, metaH));

        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource fileResource =
                new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return "transcript.txt";
                    }
                };
        body.add("file", new HttpEntity<>(fileResource, fileH));

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        return rest.postForEntity(path, entity, String.class);
    }

    private RequestExec exec(String path, HttpMethod method, String token, Map<String, ?> body)
            throws Exception {
        return new RequestExec(execRaw(path, method, token, mapper.writeValueAsString(body)));
    }

    private ResponseEntity<String> execRaw(
            String path, HttpMethod method, String token, String jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, method, new HttpEntity<>(jsonBody, headers), String.class);
    }

    private RequestExec authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new RequestExec(
                rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class));
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

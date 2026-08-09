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

/** End-to-end transcript upload flow (US07): signup -> verify -> login -> upload -> list. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class MeetingFlowIntegrationTest {

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
    void uploadFlow_listAndDetail_areTenantScoped() throws Exception {
        String email = "owner+meet@nora.dev";
        String accessToken = signupAndLogin(email, "SenhaForte123", "Owner E2E");

        // Upload US07
        String metadata =
                mapper.writeValueAsString(
                        Map.of(
                                "title",
                                "Discovery Acme",
                                "language",
                                "pt-BR",
                                "transcriptFormat",
                                "TXT",
                                "tags",
                                java.util.List.of("discovery", "renovacao"),
                                "participants",
                                java.util.List.of(
                                        Map.of(
                                                "displayName",
                                                "Lucas",
                                                "email",
                                                "lucas@acme.com",
                                                "isInternal",
                                                true))));
        String content =
                "Lucas: bom dia Marina, vamos falar sobre a renovacao.\n"
                        + "Marina: bom dia Lucas, sim, podemos sim.";

        ResponseEntity<String> uploadResp =
                multipartUpload(
                        "/meetings",
                        accessToken,
                        metadata,
                        content.getBytes(StandardCharsets.UTF_8));
        assertThat(uploadResp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode uploadBody = mapper.readTree(uploadResp.getBody());
        String meetingId = uploadBody.get("id").asText();
        assertThat(uploadBody.get("processingStatus").asText()).isEqualTo("PENDING");
        assertThat(uploadBody.get("title").asText()).isEqualTo("Discovery Acme");

        // List
        JsonNode list = authGet("/meetings", accessToken).read(HttpStatus.OK);
        assertThat(list.get("totalItems").asInt()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asText()).isEqualTo(meetingId);
        assertThat(list.get("items").get(0).get("tags").get(0).asText()).isEqualTo("discovery");

        // Detail
        JsonNode detail = authGet("/meetings/" + meetingId, accessToken).read(HttpStatus.OK);
        assertThat(detail.get("title").asText()).isEqualTo("Discovery Acme");
        assertThat(detail.get("processingStatus").asText()).isEqualTo("PENDING");
        assertThat(detail.get("participants").get(0).get("displayName").asText())
                .isEqualTo("Lucas");

        // Tenant isolation: different user, different tenant => GET returns 404
        String otherToken = signupAndLogin("otra@nora.dev", "SenhaForte123", "Other");
        ResponseEntity<String> cross = authGetRaw("/meetings/" + meetingId, otherToken);
        assertThat(cross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode otherList = authGet("/meetings", otherToken).read(HttpStatus.OK);
        assertThat(otherList.get("totalItems").asInt()).isZero();
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        String token = signupAndLogin("empty@nora.dev", "SenhaForte123", "X");
        String metadata = "{\"title\":\"x\",\"transcriptFormat\":\"TXT\"}";
        ResponseEntity<String> resp = multipartUpload("/meetings", token, metadata, new byte[0]);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("EMPTY_TRANSCRIPT");
    }

    @Test
    void uploadRejectsUnsupportedFormat() throws Exception {
        String token = signupAndLogin("fmt@nora.dev", "SenhaForte123", "X");
        String metadata = "{\"title\":\"x\",\"transcriptFormat\":\"DOCX\"}";
        ResponseEntity<String> resp =
                multipartUpload(
                        "/meetings", token, metadata, "abc".getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("UNSUPPORTED_TRANSCRIPT_FORMAT");
    }

    @Test
    void uploadRequiresAuthentication() {
        ResponseEntity<String> resp =
                rest.postForEntity("/meetings", new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name))
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken)).read(HttpStatus.NO_CONTENT);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", pwd))
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
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

    private RequestExec postJson(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(authGetRaw(path, token));
    }

    private ResponseEntity<String> authGetRaw(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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

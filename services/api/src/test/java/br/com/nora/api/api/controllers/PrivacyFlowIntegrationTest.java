package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.TranscriptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
 * LGPD — right to be forgotten (ADR 0029): DELETE /privacy/meetings/{id} PERMANENTLY deletes the
 * meeting and, by cascade, the transcript (raw_text = PII at rest). End-to-end proof + cross-tenant
 * isolation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PrivacyFlowIntegrationTest {

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
    @Autowired TranscriptRepository transcripts;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void erase_hardDeletesMeetingAndTranscript() throws Exception {
        String token = signupAndLogin("erase-owner@nora.dev", "SenhaForte123", "Owner");
        JsonNode uploaded =
                upload(
                        token,
                        "Discovery sigiloso",
                        "Marina: CPF 111.444.777-35 e cartao 4111 1111 1111 1111.");
        String meetingId = uploaded.get("id").asText();
        UUID tenantId = UUID.fromString(uploaded.get("tenantId").asText());

        // The transcript (with raw PII) exists before the erasure.
        assertThat(transcripts.findByMeetingAndTenant(UUID.fromString(meetingId), tenantId))
                .as("transcript should exist before the erasure")
                .isPresent();

        // Right to be forgotten → 204.
        assertThat(authDelete("/privacy/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The meeting is gone (404) and the list drops to zero.
        assertThat(authGetRaw("/meetings/" + meetingId, token).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode list = read(authGetRaw("/meetings", token), HttpStatus.OK);
        assertThat(list.get("totalItems").asInt()).isZero();

        // The cascade physically purged the transcript — the PII at rest no longer exists.
        assertThat(transcripts.findByMeetingAndTenant(UUID.fromString(meetingId), tenantId))
                .as("transcript (raw PII) should have been purged by the cascade")
                .isEmpty();
    }

    @Test
    void erase_nonExistentMeeting_returns404() throws Exception {
        String token = signupAndLogin("erase-404@nora.dev", "SenhaForte123", "X");
        ResponseEntity<String> resp = authDelete("/privacy/meetings/" + UUID.randomUUID(), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void erase_otherTenantsMeeting_isRejected_andDoesNotDelete() throws Exception {
        String tokenA = signupAndLogin("erase-a@nora.dev", "SenhaForte123", "A");
        String meetingId =
                upload(tokenA, "Reuniao do A", "conteudo do tenant A").get("id").asText();

        String tokenB = signupAndLogin("erase-b@nora.dev", "SenhaForte123", "B");
        // B tries to delete A's meeting → 404 (does not leak cross-tenant existence).
        assertThat(authDelete("/privacy/meetings/" + meetingId, tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A's meeting remains intact.
        assertThat(authGetRaw("/meetings/" + meetingId, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void erase_requiresAuthentication() {
        ResponseEntity<String> resp =
                rest.exchange(
                        "/privacy/meetings/" + UUID.randomUUID(),
                        HttpMethod.DELETE,
                        new HttpEntity<>(new HttpHeaders()),
                        String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    private JsonNode upload(String token, String title, String content) throws Exception {
        String metadata =
                mapper.writeValueAsString(Map.of("title", title, "transcriptFormat", "TXT"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders metaH = new HttpHeaders();
        metaH.setContentType(MediaType.APPLICATION_JSON);
        body.add("metadata", new HttpEntity<>(metadata, metaH));
        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource file =
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "t.txt";
                    }
                };
        body.add("file", new HttpEntity<>(file, fileH));

        ResponseEntity<String> resp =
                rest.postForEntity("/meetings", new HttpEntity<>(body, headers), String.class);
        return read(resp, HttpStatus.ACCEPTED);
    }

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        JsonNode signup =
                read(
                        rest.postForEntity(
                                "/auth/signup",
                                new HttpEntity<>(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email", email,
                                                        "password", pwd,
                                                        "displayName", name)),
                                        json),
                                String.class),
                        HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        read(
                rest.postForEntity(
                        "/auth/verify-email",
                        new HttpEntity<>(
                                mapper.writeValueAsString(Map.of("token", verifyToken)), json),
                        String.class),
                HttpStatus.NO_CONTENT);
        JsonNode login =
                read(
                        rest.postForEntity(
                                "/auth/login",
                                new HttpEntity<>(
                                        mapper.writeValueAsString(
                                                Map.of("email", email, "password", pwd)),
                                        json),
                                String.class),
                        HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private ResponseEntity<String> authDelete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> authGetRaw(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> resp, HttpStatus expected) throws Exception {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }
}

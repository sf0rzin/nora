package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.EmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RAG backfill (GET/POST /admin/platform/embeddings/backfill). Reproduces the defect it exists to
 * fix: a meeting analysed while no embedding credential was configured has a summary but no vector,
 * so semantic search cannot see it, and nothing ever comes back for it.
 *
 * <p>The async analysis pipeline does not run in the test profile, so the summary snippet is
 * written straight to the row — which is exactly the state a meeting is left in when the analysis
 * succeeded and the indexing did not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(EmbeddingBackfillIntegrationTest.StubEmbeddingConfig.class)
class EmbeddingBackfillIntegrationTest {

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
        registry.add("nora.platform.admin-token", () -> "test-admin");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void backfill_indexesAnalysedMeetingsWithoutReanalysing_andIsIdempotent() throws Exception {
        String token = signupAndLogin("backfill-owner@nora.dev", "SenhaForte123", "Owner");
        JsonNode a = upload(token, "Renovação Acme");
        JsonNode b = upload(token, "Onboarding cliente");
        UUID tenantId = UUID.fromString(a.get("tenantId").asText());
        UUID idA = UUID.fromString(a.get("id").asText());
        UUID idB = UUID.fromString(b.get("id").asText());

        // The state the defect leaves behind: analysed (there is a summary) and unindexed.
        setSnippet(idA, "renovação de contrato e negociação de preço com a Acme");
        setSnippet(idB, "onboarding de novo cliente e configuração de suporte");

        // Search is blind to both, and says so by returning nothing at all.
        JsonNode before = read(authGet("/meetings/search?q=" + enc("preço do contrato"), token));
        assertThat(before.get("items")).isEmpty();

        // The preview costs nothing and counts them.
        JsonNode preview = read(adminGet("/admin/platform/embeddings/backfill"));
        assertThat(preview.get("enabled").asBoolean()).isTrue();
        assertThat(tenantRow(preview, tenantId).get("missingVector").asLong()).isEqualTo(2);

        // One run indexes both, without touching the analysis.
        JsonNode first = read(adminPost(Map.of("tenantId", tenantId.toString())));
        assertThat(first.get("indexed").asInt()).isEqualTo(2);
        assertThat(first.get("failed").asInt()).isZero();
        assertThat(first.get("remaining").asLong()).isZero();

        // And now the meeting that discusses pricing is findable.
        JsonNode found =
                read(authGet("/meetings/search?q=" + enc("renegociar o preço do contrato"), token));
        assertThat(found.get("items")).isNotEmpty();
        assertThat(found.get("items").get(0).get("id").asText()).isEqualTo(idA.toString());

        // Idempotent: nothing is a candidate the second time, so nothing is billed again.
        JsonNode second = read(adminPost(Map.of("tenantId", tenantId.toString())));
        assertThat(second.get("candidates").asInt()).isZero();
        assertThat(second.get("indexed").asInt()).isZero();

        // The other half of the same defect: the row survives a model switch but the vector space
        // does not, so the search ignores it exactly as completely as a missing row.
        jdbc.update(
                "UPDATE meeting_embeddings SET model = 'other:model' WHERE meeting_id = ?", idA);
        JsonNode afterSwitch = read(adminGet("/admin/platform/embeddings/backfill"));
        assertThat(tenantRow(afterSwitch, tenantId).get("staleModel").asLong()).isEqualTo(1);
        JsonNode third = read(adminPost(Map.of("tenantId", tenantId.toString())));
        assertThat(third.get("indexed").asInt()).isEqualTo(1);
        assertThat(third.get("remaining").asLong()).isZero();
    }

    @Test
    void backfill_requiresATenant() throws Exception {
        ResponseEntity<String> r = adminPost(Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /* ---------- helpers ---------- */

    private void setSnippet(UUID meetingId, String snippet) {
        jdbc.update("UPDATE meetings SET summary_snippet = ? WHERE id = ?", snippet, meetingId);
    }

    private JsonNode tenantRow(JsonNode preview, UUID tenantId) {
        for (JsonNode row : preview.get("tenants")) {
            if (tenantId.toString().equals(row.get("tenantId").asText())) {
                return row;
            }
        }
        throw new AssertionError("tenant " + tenantId + " absent from the preview: " + preview);
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> adminGet(String path) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Internal-Token", "test-admin");
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }

    private ResponseEntity<String> adminPost(Map<String, Object> body) throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Internal-Token", "test-admin");
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(
                "/admin/platform/embeddings/backfill",
                HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), h),
                String.class);
    }

    private JsonNode upload(String token, String title) throws Exception {
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
                new ByteArrayResource("conteudo da reuniao".getBytes(StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "t.txt";
                    }
                };
        body.add("file", new HttpEntity<>(file, fileH));
        return read(rest.postForEntity("/meetings", new HttpEntity<>(body, headers), String.class));
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
                                String.class));
        read(
                rest.postForEntity(
                        "/auth/verify-email",
                        new HttpEntity<>(
                                mapper.writeValueAsString(
                                        Map.of(
                                                "token",
                                                signup.get("emailVerificationDevToken").asText())),
                                json),
                        String.class));
        JsonNode login =
                read(
                        rest.postForEntity(
                                "/auth/login",
                                new HttpEntity<>(
                                        mapper.writeValueAsString(
                                                Map.of("email", email, "password", pwd)),
                                        json),
                                String.class));
        return login.get("accessToken").asText();
    }

    private ResponseEntity<String> authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode read(ResponseEntity<String> resp) throws Exception {
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("body=%s", resp.getBody()).isTrue();
        return resp.getBody() == null ? mapper.createObjectNode() : mapper.readTree(resp.getBody());
    }

    @TestConfiguration
    static class StubEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingClient stubEmbeddingClient() {
            return new EmbeddingClient() {
                @Override
                public float[] embed(String text) {
                    float[] v = new float[64];
                    for (String w : text.toLowerCase().split("\\W+")) {
                        if (!w.isBlank()) {
                            v[Math.floorMod(w.hashCode(), 64)] += 1f;
                        }
                    }
                    return v;
                }

                @Override
                public String modelId() {
                    return "stub:test";
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }
            };
        }
    }
}

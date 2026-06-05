package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.embedding.EmbeddingService;
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
 * RAG — busca semântica (GET /meetings/search). Stuba o {@link EmbeddingClient} com um embedding
 * determinístico (bag-of-words por hash), então overlap de palavras vira similaridade de cosseno.
 * Prova: a reunião RELEVANTE à query rankeia primeiro, e a busca é tenant-scoped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(RagSearchIntegrationTest.StubEmbeddingConfig.class)
class RagSearchIntegrationTest {

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
    @Autowired EmbeddingService embeddings;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void search_ranksRelevantMeetingFirst() throws Exception {
        String token = signupAndLogin("rag-owner@nora.dev", "SenhaForte123", "Owner");
        JsonNode a = upload(token, "Renovação Acme");
        JsonNode b = upload(token, "Onboarding cliente");
        UUID tenantId = UUID.fromString(a.get("tenantId").asText());
        UUID idA = UUID.fromString(a.get("id").asText());
        UUID idB = UUID.fromString(b.get("id").asText());

        // Indexa os resumos (texto distinto). No profile test o pipeline async não roda; indexamos
        // direto pra controlar o conteúdo do embedding.
        embeddings.index(idA, tenantId, "renovação de contrato e negociação de preço com a Acme");
        embeddings.index(idB, tenantId, "onboarding de novo cliente e configuração de suporte");

        // Query semanticamente próxima de A.
        JsonNode res =
                read(authGet("/meetings/search?q=" + enc("renegociar o preço do contrato"), token));
        assertThat(res.get("items")).isNotEmpty();
        assertThat(res.get("items").get(0).get("id").asText())
                .as("a reunião de renovação/preço deve rankear primeiro")
                .isEqualTo(idA.toString());
    }

    @Test
    void search_isTenantScoped() throws Exception {
        String tokenA = signupAndLogin("rag-a@nora.dev", "SenhaForte123", "A");
        JsonNode a = upload(tokenA, "Segredo do A");
        UUID tenantA = UUID.fromString(a.get("tenantId").asText());
        embeddings.index(
                UUID.fromString(a.get("id").asText()),
                tenantA,
                "informação confidencial do tenant A");

        // Tenant B busca o mesmo termo → não vê nada do A.
        String tokenB = signupAndLogin("rag-b@nora.dev", "SenhaForte123", "B");
        JsonNode res =
                read(authGet("/meetings/search?q=" + enc("informação confidencial"), tokenB));
        assertThat(res.get("items")).isEmpty();
    }

    /* ---------- helpers ---------- */

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
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

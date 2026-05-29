package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova que as chains do control plane são escopadas por token interno SEM enfraquecer a chain JWT
 * por-tenant (catch-all). Plataforma OFF (default) basta: /internal/llm-config responde via
 * fallback SOFT (200), /admin responde 503 (auth ok, banco off), e um endpoint de tenant ignora o
 * token interno (401 — chain JWT intacta).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class PlatformSecurityIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("nora.platform.internal-token", () -> "test-internal");
        registry.add("nora.platform.admin-token", () -> "test-admin");
    }

    @Autowired TestRestTemplate rest;

    @Test
    void internalLlmConfig_comTokenService_200() {
        ResponseEntity<String> r =
                get("/internal/platform/llm-config?service=chat", "test-internal");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("provider");
    }

    @Test
    void internalLlmConfig_semToken_401() {
        assertThat(get("/internal/platform/llm-config?service=chat", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void internalLlmConfig_tokenErrado_401() {
        assertThat(get("/internal/platform/llm-config?service=chat", "errado").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void adminModels_comAdminToken_plataformaOff_503() {
        assertThat(get("/admin/platform/models", "test-admin").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void adminModels_semToken_401() {
        assertThat(get("/admin/platform/models", null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void chainSeparada_tenantEndpointIgnoraTokenInterno_401() {
        // /meetings pertence à chain JWT por-tenant (catch-all). O token interno NÃO concede
        // acesso.
        assertThat(get("/meetings", "test-internal").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> get(String path, String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) {
            h.add("X-Internal-Token", token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(h), String.class);
    }
}

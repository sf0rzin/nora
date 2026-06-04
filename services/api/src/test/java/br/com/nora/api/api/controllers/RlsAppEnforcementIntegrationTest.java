package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.AnalysisResult;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prova de que o app FUNCIONA sob RLS enforce REAL (ADR 0028) — não só o isolamento no nível do
 * banco (isso é o {@link RlsEnforcementIntegrationTest}), mas o app inteiro rodando como o role
 * NOBYPASSRLS, ponta-a-ponta pela API HTTP.
 *
 * <p>Setup que espelha o cutover de produção:
 *
 * <ul>
 *   <li>o app runtime conecta como {@code nora_app_test} (NOBYPASSRLS → RLS vale de verdade);
 *   <li>o Flyway conecta como o owner/admin do container (DDL + dono das tabelas);
 *   <li>{@code nora.security.rls.enforce=true} → o {@code TenantRlsAspect} seta o GUC por
 *       transação.
 * </ul>
 *
 * <p>O que isto garante e os ITs com conexão owner NÃO garantiriam:
 *
 * <ol>
 *   <li><b>Auth não quebra</b> sob enforce: signup/verify/login operam em tabelas de IDENTIDADE que
 *       a V020 exemptou (senão fail-closed sem GUC). ⬅️ o furo que o ADR 0026 não viu.
 *   <li><b>Tabelas enforced funcionam</b> para o tenant autenticado (upload cria
 *       meeting/transcript/ participantes; list/detail leem) — o aspect seta o GUC a partir do JWT.
 *   <li><b>Isolamento real</b>: o tenant B NÃO enxerga o meeting do A — agora pelo Postgres, não só
 *       pelo filtro da aplicação.
 *   <li><b>Pipeline async</b> escreve sob enforce: a análise (thread de executor) completa,
 *       provando que o TaskDecorator propagou o tenant pro GUC fora da thread do request.
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(RlsAppEnforcementIntegrationTest.StubWorkerConfig.class)
class RlsAppEnforcementIntegrationTest {

    private static final String APP_ROLE = "nora_app_test";
    private static final String APP_PASSWORD = "nora_app_test_pwd";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        // Runtime = nora_app_test (NOBYPASSRLS, criado no @BeforeAll). Flyway = admin (owner do
        // container). Enforce ON. Suppliers lazy — avaliados no context-load, com o container up.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("nora.security.rls.enforce", () -> "true");
    }

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    /**
     * Cria o role NOBYPASSRLS ANTES do contexto Spring subir. {@code @BeforeAll} roda depois do
     * Testcontainers iniciar o container, mas ANTES do context-load ({@code @DynamicPropertySource}
     * + Flyway + pool da API) — então o role já existe quando a pool de runtime conecta como ele.
     */
    @BeforeAll
    static void createRuntimeRole() throws Exception {
        try (Connection c =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("DROP ROLE IF EXISTS " + APP_ROLE);
            s.execute(
                    "CREATE ROLE "
                            + APP_ROLE
                            + " WITH LOGIN PASSWORD '"
                            + APP_PASSWORD
                            + "' NOBYPASSRLS");
        }
    }

    private static boolean granted = false;

    @BeforeEach
    void setUp() throws Exception {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        grantRuntimeRoleOnce();
    }

    /**
     * Grants do nora_app (espelha o R001) — precisam do schema + da função {@code
     * nora.current_tenant_id()}, criados pelo Flyway no context-load; rodam aqui (pós-load) uma
     * vez. O app boota antes disso fazendo só {@code SELECT 1} de health (sem grant), então a pool
     * sobe; os grants chegam antes da 1ª chamada de API.
     */
    private static synchronized void grantRuntimeRoleOnce() throws Exception {
        if (granted) {
            return;
        }
        try (Connection c =
                        DriverManager.getConnection(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            s.execute("GRANT USAGE ON SCHEMA public TO " + APP_ROLE);
            s.execute("GRANT USAGE ON SCHEMA nora TO " + APP_ROLE);
            s.execute("GRANT EXECUTE ON FUNCTION nora.current_tenant_id() TO " + APP_ROLE);
            s.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                            + APP_ROLE);
            s.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO " + APP_ROLE);
        }
        granted = true;
    }

    @Test
    void authFlows_workUnderEnforce() throws Exception {
        // Se a V020 não tivesse exemptado as tabelas de identidade, isto falharia (fail-closed).
        String token = signupAndLogin("auth-enforce@nora.dev", "SenhaForte123", "Auth Enforce");
        assertThat(token).isNotBlank();
        // Leitura enforced autenticada (lista vazia) funciona — aspect setou o GUC do JWT.
        JsonNode list = authGet("/meetings", token).read(HttpStatus.OK);
        assertThat(list.get("totalItems").asInt()).isZero();
    }

    @Test
    void upload_listDetail_andCrossTenantIsolation_underEnforce() throws Exception {
        String aToken = signupAndLogin("tenant-a@nora.dev", "SenhaForte123", "Tenant A");
        String metadata =
                mapper.writeValueAsString(
                        Map.of(
                                "title",
                                "Reuniao secreta A",
                                "language",
                                "pt-BR",
                                "transcriptFormat",
                                "TXT",
                                "tags",
                                List.of("venda")));
        String content = "A: conteudo confidencial do tenant A.\nB: ok, combinado.";

        ResponseEntity<String> upload =
                multipartUpload(
                        "/meetings", aToken, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String meetingId = mapper.readTree(upload.getBody()).get("id").asText();

        // A enxerga (escrita+leitura em tabela enforced com o GUC do A).
        JsonNode aList = authGet("/meetings", aToken).read(HttpStatus.OK);
        assertThat(aList.get("totalItems").asInt()).isEqualTo(1);
        JsonNode aDetail = authGet("/meetings/" + meetingId, aToken).read(HttpStatus.OK);
        assertThat(aDetail.get("title").asText()).isEqualTo("Reuniao secreta A");

        // B (outro tenant) NÃO enxerga — isolamento garantido pelo Postgres, não só pela app.
        String bToken = signupAndLogin("tenant-b@nora.dev", "SenhaForte123", "Tenant B");
        ResponseEntity<String> bCross = authGetRaw("/meetings/" + meetingId, bToken);
        assertThat(bCross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode bList = authGet("/meetings", bToken).read(HttpStatus.OK);
        assertThat(bList.get("totalItems").asInt()).isZero();
    }

    @Test
    void asyncAnalysisPipeline_completesUnderEnforce() throws Exception {
        String token = signupAndLogin("analysis-enforce@nora.dev", "SenhaForte123", "Analysis");
        String metadata = "{\"title\":\"Analise sob enforce\",\"transcriptFormat\":\"TXT\"}";
        String content = "Joao: vamos fechar o contrato.\nMaria: perfeito, fechado.";

        ResponseEntity<String> upload =
                multipartUpload(
                        "/meetings", token, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String meetingId = mapper.readTree(upload.getBody()).get("id").asText();

        // O pipeline async (thread de executor) escreve meeting_analyses sob enforce. Se o
        // TaskDecorator não propagasse o tenant, a escrita seria fail-closed e o status nunca
        // chegaria a COMPLETED (ficaria PROCESSING ou FAILED).
        String status = pollProcessingStatus(meetingId, token);
        assertThat(status).isEqualTo("COMPLETED");
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.has("analysis")).isTrue();
    }

    /* ---------- helpers ---------- */

    private String pollProcessingStatus(String meetingId, String token) throws Exception {
        for (int i = 0; i < 40; i++) {
            JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
            String status = detail.get("processingStatus").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "TIMEOUT";
    }

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

        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
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

    /**
     * Stub determinístico do worker NLP — não há worker rodando no IT. Faz a análise async
     * completar (escrevendo {@code meeting_analyses} sob enforce), provando que o TaskDecorator
     * propagou o tenant pro GUC na thread do executor. Nested @TestConfiguration auto-detectada
     * pelo {@code @SpringBootTest}.
     */
    @TestConfiguration
    static class StubWorkerConfig {
        @Bean
        @Primary
        NlpWorkerClient stubWorker() {
            return new NlpWorkerClient() {
                @Override
                public AnalysisResult analyze(
                        UUID meetingId,
                        UUID tenantId,
                        String language,
                        String transcript,
                        Optional<TenantContext> tenantContext,
                        Optional<br.com.nora.api.domain.meeting.productivity.MeetingGoal> goal) {
                    MeetingAnalysis analysis =
                            MeetingAnalysis.newAnalysis(
                                    meetingId,
                                    tenantId,
                                    "Resumo stub (RLS enforce IT).",
                                    Sentiment.NEUTRAL,
                                    List.of("topico1"),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    "stub-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    0);
                    return AnalysisResult.of(analysis, null, null);
                }

                @Override
                public WorkerDtos.LiveAnalyzeResponse analyzeLive(
                        String transcriptChunk,
                        String language,
                        WorkerDtos.LiveHighlights previousHighlights) {
                    return new WorkerDtos.LiveAnalyzeResponse(
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            new WorkerDtos.LiveMetadata(0, 0, 0, 0, "stub-live"));
                }
            };
        }
    }
}

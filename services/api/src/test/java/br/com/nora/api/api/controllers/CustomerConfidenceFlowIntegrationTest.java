package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.application.ports.NlpWorkerClient.CustomerConfidenceCarrier;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.BuyingSignalType;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.customer.ObjectionSeverity;
import br.com.nora.api.domain.customer.ObjectionType;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
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
 * Fluxo end-to-end do Customer Confidence (ADR 0015): upload -> analise stub que emite o bloco
 * customerConfidence -> conta auto-criada (dedup por nome) -> assessment persistido -> trend
 * recalculado server-side na 2a reuniao -> GET /meetings/{id} retorna customerConfidence. Cobre
 * tambem reuniao interna (sem accountName) => sem conta e campo null na resposta.
 *
 * <p>O worker e stubado por um bean @Primary (mesmo padrao do ProductivityFlowIntegrationTest); o
 * carrier de confidence emitido e controlado por {@link ConfidenceScript}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(CustomerConfidenceFlowIntegrationTest.StubWorkerConfig.class)
class CustomerConfidenceFlowIntegrationTest {

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
    @Autowired AnalysisService analysisService;
    @Autowired ConfidenceScript script;

    @BeforeEach
    void setUp() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        script.reset();
    }

    @Test
    void externalMeetingCreatesAccountAndPersistsConfidence() throws Exception {
        String token = signupAndLogin("conf@nora.dev", "SenhaForte123", "Owner Conf");
        UUID tenantId = principalTenantId(token);
        UUID meetingId = uploadMeeting(token, "Discovery Acme Corp");

        // Worker emite confidence para a conta "Acme Corp" (score 70).
        script.set(carrier("Acme Corp", 70, ConfidenceBand.MEDIUM));
        analysisService.run(meetingId, tenantId);

        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        JsonNode cc = detail.get("customerConfidence");
        assertThat(cc.isNull()).isFalse();
        assertThat(cc.get("score").asInt()).isEqualTo(70);
        assertThat(cc.get("band").asText()).isEqualTo("MEDIUM");
        assertThat(cc.get("accountName").asText()).isEqualTo("Acme Corp");
        // Primeira reuniao da conta => sem trend.
        assertThat(cc.get("trend").isNull()).isTrue();
        assertThat(cc.get("buyingSignals").size()).isEqualTo(1);
        assertThat(cc.get("buyingSignals").get(0).get("type").asText())
                .isEqualTo("BUDGET_DISCUSSED");
        assertThat(cc.get("objections").size()).isEqualTo(1);
        assertThat(cc.get("objections").get(0).get("type").asText()).isEqualTo("PRICE");
        assertThat(cc.get("objections").get(0).get("competitor").asText())
                .isEqualTo("Concorrente X");
        assertThat(cc.get("rationale").asText()).contains("stub");
    }

    @Test
    void secondMeetingSameAccountDedupesAndComputesTrend() throws Exception {
        String token = signupAndLogin("trend@nora.dev", "SenhaForte123", "Owner Trend");
        UUID tenantId = principalTenantId(token);

        // 1a reuniao: conta "Globex" criada, score 50.
        UUID meeting1 = uploadMeeting(token, "Call Globex #1");
        script.set(carrier("Globex", 50, ConfidenceBand.MEDIUM));
        analysisService.run(meeting1, tenantId);
        JsonNode d1 = authGet("/meetings/" + meeting1, token).read(HttpStatus.OK);
        assertThat(d1.get("customerConfidence").get("trend").isNull()).isTrue();

        // 2a reuniao, mesma conta (case-insensitive "globex"), score 80 => IMPROVING.
        UUID meeting2 = uploadMeeting(token, "Call Globex #2");
        script.set(carrier("globex", 80, ConfidenceBand.HIGH));
        analysisService.run(meeting2, tenantId);
        JsonNode d2 = authGet("/meetings/" + meeting2, token).read(HttpStatus.OK);
        JsonNode cc2 = d2.get("customerConfidence");
        assertThat(cc2.get("score").asInt()).isEqualTo(80);
        assertThat(cc2.get("trend").asText()).isEqualTo("IMPROVING");
        // Dedup: nome canonico mantem o original ("Globex") da 1a criacao.
        assertThat(cc2.get("accountName").asText()).isEqualTo("Globex");
    }

    @Test
    void internalMeetingPersistsNoConfidence() throws Exception {
        String token = signupAndLogin("internal@nora.dev", "SenhaForte123", "Owner Int");
        UUID tenantId = principalTenantId(token);
        UUID meetingId = uploadMeeting(token, "Retro interna do time");

        // Worker NAO emite confidence (reuniao interna).
        script.set(null);
        analysisService.run(meetingId, tenantId);

        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(detail.get("customerConfidence").isNull()).isTrue();
    }

    @Test
    void confidenceIsTenantIsolated() throws Exception {
        String tokenA = signupAndLogin("cca@nora.dev", "SenhaForte123", "A");
        String tokenB = signupAndLogin("ccb@nora.dev", "SenhaForte123", "B");
        UUID tenantA = principalTenantId(tokenA);
        UUID meetingA = uploadMeeting(tokenA, "Reuniao tenant A confidence");

        script.set(carrier("Initech", 60, ConfidenceBand.MEDIUM));
        analysisService.run(meetingA, tenantA);

        // Tenant B nao enxerga a meeting de A => 404 no GET.
        ResponseEntity<String> resp = authGetRaw("/meetings/" + meetingA, tokenB);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /* ---------- builders ---------- */

    private static CustomerConfidenceCarrier carrier(
            String accountName, int score, ConfidenceBand band) {
        BuyingSignal signal =
                new BuyingSignal(
                        BuyingSignalType.BUDGET_DISCUSSED,
                        "Temos orcamento aprovado para este trimestre",
                        0.8,
                        0);
        Objection objection =
                new Objection(
                        ObjectionType.PRICE,
                        "O preco esta acima do que esperavamos",
                        ObjectionSeverity.MEDIUM,
                        "Concorrente X",
                        0);
        // trend abaixo e um palpite do worker; o backend o ignora.
        return new CustomerConfidenceCarrier(
                accountName,
                score,
                band,
                ConfidenceTrend.STABLE,
                List.of(signal),
                List.of(objection),
                "rationale do stub para o customer confidence em teste integrado");
    }

    /* ---------- helpers (espelham ProductivityFlowIntegrationTest) ---------- */

    private UUID principalTenantId(String token) throws Exception {
        UUID meeting = uploadMeeting(token, "tmp tenant probe " + UUID.randomUUID());
        JsonNode detail = authGet("/meetings/" + meeting, token).read(HttpStatus.OK);
        return UUID.fromString(detail.get("tenantId").asText());
    }

    private UUID uploadMeeting(String token, String title) throws Exception {
        String metadata =
                mapper.writeValueAsString(
                        Map.of("title", title, "language", "pt-BR", "transcriptFormat", "TXT"));
        String content =
                "Lucas: bom dia. Marina: bom dia.\n"
                        + "Lucas: vamos discutir o tema da reuniao. Marina: certo.";
        ResponseEntity<String> resp =
                multipartUpload(
                        "/meetings", token, metadata, content.getBytes(StandardCharsets.UTF_8));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = mapper.readTree(resp.getBody());
        return UUID.fromString(body.get("id").asText());
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

    /**
     * Estado controlavel do bloco customerConfidence que o stub do worker deve emitir na proxima
     * analise. Permite cada teste declarar o carrier (ou null para reuniao interna).
     */
    static final class ConfidenceScript {
        private final AtomicReference<CustomerConfidenceCarrier> next = new AtomicReference<>();

        void set(CustomerConfidenceCarrier carrier) {
            next.set(carrier);
        }

        void reset() {
            next.set(null);
        }

        CustomerConfidenceCarrier current() {
            return next.get();
        }
    }

    /** Stub determinista do worker. Emite o confidence definido por {@link ConfidenceScript}. */
    @TestConfiguration
    static class StubWorkerConfig {

        @Bean
        ConfidenceScript confidenceScript() {
            return new ConfidenceScript();
        }

        @Bean
        @Primary
        NlpWorkerClient stubWorker(ConfidenceScript scriptBean) {
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
                                    "Resumo stub para a reuniao em teste integrado.",
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
                    return AnalysisResult.of(analysis, null, scriptBean.current());
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

package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
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
 * Fluxo end-to-end do NORA Flows (Fase 0 do GOAL): upload → análise COMPLETED → evento pós-commit →
 * WorkflowEngine → ação send_email executada de verdade (capturada pelo {@link
 * RecordingEmailSender}) → histórico de execução com log. Também cobre CRUD, validação de
 * definition, "Testar" síncrono, condições e isolamento de tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class WorkflowFlowIntegrationTest {

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

    static final RecordingEmailSender EMAILS = new RecordingEmailSender();

    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired AnalysisService analysisService;

    @BeforeEach
    void setup() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        EMAILS.workflowEmails.clear();
    }

    /* =========================== cenário-âncora =========================== */

    @Test
    void uploadDisparaWorkflow_enviaEmailEGravaExecucao() throws Exception {
        String token = signupAndLogin("flows-anchor@nora.dev", "SenhaForte123", "Flows Anchor");

        JsonNode created =
                postJson(
                                "/workflows",
                                workflowBody(
                                        "Notificar análise concluída",
                                        triggerToEmailDefinition("anchor-dest@nora.dev")),
                                token)
                        .read(HttpStatus.CREATED);
        String workflowId = created.get("id").asText();
        assertThat(created.get("triggerType").asText()).isEqualTo("meeting.analysis_completed");
        assertThat(created.get("active").asBoolean()).isTrue();

        String meetingId = uploadMeeting(token, "Discovery Acme");
        runAnalysis(meetingId, token);

        JsonNode execution = awaitExecutions(workflowId, token, 1).get(0);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("eventType").asText()).isEqualTo("meeting.analysis_completed");
        String logText = execution.get("log").toString();
        assertThat(logText).contains("Gatilho");
        assertThat(logText).contains("E-mail enviado para anchor-dest@nora.dev");

        assertThat(EMAILS.workflowEmails).hasSize(1);
        RecordingEmailSender.Sent sent = EMAILS.workflowEmails.get(0);
        assertThat(sent.to()).isEqualTo("anchor-dest@nora.dev");
        assertThat(sent.subject()).contains("Discovery Acme");
        assertThat(sent.html()).contains("Resumo stub do NORA Flows");
        assertThat(sent.html()).contains("/meetings/" + meetingId);
    }

    @Test
    void workflowInativo_naoDispara() throws Exception {
        String token = signupAndLogin("flows-inactive@nora.dev", "SenhaForte123", "Inactive");

        JsonNode created =
                postJson(
                                "/workflows",
                                Map.of(
                                        "name",
                                        "Desligado",
                                        "active",
                                        false,
                                        "definition",
                                        mapper.readTree(
                                                triggerToEmailDefinition("inactive@nora.dev"))),
                                token)
                        .read(HttpStatus.CREATED);
        String workflowId = created.get("id").asText();

        String meetingId = uploadMeeting(token, "Reunião sem fluxo");
        runAnalysis(meetingId, token);
        // Janela generosa pro listener async ter rodado se fosse rodar.
        Thread.sleep(1500);

        JsonNode executions =
                authGet("/workflows/" + workflowId + "/executions", token).read(HttpStatus.OK);
        assertThat(executions.size()).isZero();
        assertThat(EMAILS.workflowEmails).isEmpty();
    }

    @Test
    void condicaoNaoSatisfeita_interrompeCaminhoSemEnviarEmail() throws Exception {
        String token = signupAndLogin("flows-cond@nora.dev", "SenhaForte123", "Cond");

        String definition =
                """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                     "position":{"x":0,"y":0}},
                    {"id":"c1","kind":"condition","type":"productivity_score_below",
                     "params":{"value":70},"position":{"x":200,"y":0}},
                    {"id":"a1","kind":"action","type":"send_email",
                     "params":{"to":"cond-dest@nora.dev"},"position":{"x":400,"y":0}}
                  ],
                  "edges": [
                    {"id":"e1","source":"t1","target":"c1"},
                    {"id":"e2","source":"c1","target":"a1"}
                  ]
                }
                """;
        JsonNode created =
                postJson("/workflows", workflowBody("Alerta produtividade", definition), token)
                        .read(HttpStatus.CREATED);
        String workflowId = created.get("id").asText();

        // Upload sem goal => análise sem productivity => condição "score < 70" NÃO satisfeita.
        String meetingId = uploadMeeting(token, "Reunião sem goal");
        runAnalysis(meetingId, token);

        JsonNode execution = awaitExecutions(workflowId, token, 1).get(0);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        String logText = execution.get("log").toString();
        assertThat(logText).contains("condição não satisfeita");
        assertThat(logText).doesNotContain("E-mail enviado");
        assertThat(EMAILS.workflowEmails).isEmpty();
    }

    /* =========================== testar (síncrono) =========================== */

    @Test
    void testar_comReuniaoAnalisada_usaDadosReais() throws Exception {
        String token = signupAndLogin("flows-test@nora.dev", "SenhaForte123", "Testar");

        String meetingId = uploadMeeting(token, "Kickoff Beta");
        runAnalysis(meetingId, token);

        // Workflow criado DEPOIS da análise: a única execução vem do POST /test.
        JsonNode created =
                postJson(
                                "/workflows",
                                workflowBody(
                                        "Teste manual",
                                        triggerToEmailDefinition("manual@nora.dev")),
                                token)
                        .read(HttpStatus.CREATED);
        String workflowId = created.get("id").asText();

        JsonNode execution =
                postJson("/workflows/" + workflowId + "/test", Map.of(), token).read(HttpStatus.OK);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("log").toString())
                .contains("Kickoff Beta")
                .doesNotContain("dados de exemplo");
        assertThat(EMAILS.workflowEmails).hasSize(1);
        assertThat(EMAILS.workflowEmails.get(0).subject()).contains("Kickoff Beta");

        // Execução do teste fica persistida no histórico.
        JsonNode executions =
                authGet("/workflows/" + workflowId + "/executions", token).read(HttpStatus.OK);
        assertThat(executions.size()).isEqualTo(1);
    }

    @Test
    void testar_semReuniao_usaDadosDeExemplo() throws Exception {
        String token = signupAndLogin("flows-sample@nora.dev", "SenhaForte123", "Sample");

        JsonNode created =
                postJson(
                                "/workflows",
                                workflowBody(
                                        "Teste sem reunião",
                                        triggerToEmailDefinition("sample@nora.dev")),
                                token)
                        .read(HttpStatus.CREATED);

        JsonNode execution =
                postJson("/workflows/" + created.get("id").asText() + "/test", Map.of(), token)
                        .read(HttpStatus.OK);
        assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(execution.get("log").toString()).contains("dados de exemplo");
        assertThat(EMAILS.workflowEmails).hasSize(1);
    }

    /* =========================== CRUD + validação =========================== */

    @Test
    void crud_criaAtualizaDeleta() throws Exception {
        String token = signupAndLogin("flows-crud@nora.dev", "SenhaForte123", "Crud");

        JsonNode created =
                postJson(
                                "/workflows",
                                workflowBody("Original", triggerToEmailDefinition("crud@nora.dev")),
                                token)
                        .read(HttpStatus.CREATED);
        String id = created.get("id").asText();

        JsonNode list = authGet("/workflows", token).read(HttpStatus.OK);
        assertThat(list.size()).isEqualTo(1);

        JsonNode updated =
                putJson(
                                "/workflows/" + id,
                                Map.of(
                                        "name",
                                        "Renomeado",
                                        "active",
                                        false,
                                        "definition",
                                        mapper.readTree(triggerToEmailDefinition("crud@nora.dev"))),
                                token)
                        .read(HttpStatus.OK);
        assertThat(updated.get("name").asText()).isEqualTo("Renomeado");
        assertThat(updated.get("active").asBoolean()).isFalse();

        ResponseEntity<String> deleted =
                rest.exchange(
                        "/workflows/" + id,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(token)),
                        String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> gone = authGetRaw("/workflows/" + id, token);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void definitionInvalida_retorna422() throws Exception {
        String token = signupAndLogin("flows-invalid@nora.dev", "SenhaForte123", "Invalid");

        // Sem ação ligada ao gatilho.
        String semAcao =
                """
                {"nodes":[{"id":"t1","kind":"trigger","type":"meeting.analysis_completed"}],
                 "edges":[]}
                """;
        ResponseEntity<String> resp =
                postJsonRaw("/workflows", workflowBody("Inválido", semAcao), token);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(mapper.readTree(resp.getBody()).get("code").asText())
                .isEqualTo("WORKFLOW_INVALID_DEFINITION");

        // send_email sem destinatário.
        String semDestinatario =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email"}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        ResponseEntity<String> resp2 =
                postJsonRaw("/workflows", workflowBody("Inválido 2", semDestinatario), token);
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /* =========================== isolamento de tenant =========================== */

    @Test
    void crossTenant_retorna404() throws Exception {
        String tokenA = signupAndLogin("flows-a@nora.dev", "SenhaForte123", "A");
        String tokenB = signupAndLogin("flows-b@nora.dev", "SenhaForte123", "B");

        JsonNode created =
                postJson(
                                "/workflows",
                                workflowBody("Do tenant A", triggerToEmailDefinition("a@nora.dev")),
                                tokenA)
                        .read(HttpStatus.CREATED);
        String id = created.get("id").asText();

        assertThat(authGetRaw("/workflows/" + id, tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(authGetRaw("/workflows/" + id + "/executions", tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postJsonRaw("/workflows/" + id + "/test", Map.of(), tokenB).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<String> deleteCross =
                rest.exchange(
                        "/workflows/" + id,
                        HttpMethod.DELETE,
                        new HttpEntity<>(authHeaders(tokenB)),
                        String.class);
        assertThat(deleteCross.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode listB = authGet("/workflows", tokenB).read(HttpStatus.OK);
        assertThat(listB.size()).isZero();
    }

    @Test
    void exigeAutenticacao() {
        ResponseEntity<String> resp =
                rest.postForEntity("/workflows", new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* =========================== helpers =========================== */

    private Map<String, Object> workflowBody(String name, String definitionJson) throws Exception {
        return Map.of("name", name, "definition", mapper.readTree(definitionJson));
    }

    private String triggerToEmailDefinition(String to) {
        return """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                     "position":{"x":0,"y":0}},
                    {"id":"a1","kind":"action","type":"send_email",
                     "params":{"to":"%s"},"position":{"x":280,"y":0}}
                  ],
                  "edges": [{"id":"e1","source":"t1","target":"a1"}]
                }
                """
                .formatted(to);
    }

    private String uploadMeeting(String token, String title) throws Exception {
        String metadata =
                mapper.writeValueAsString(
                        Map.of(
                                "title",
                                title,
                                "language",
                                "pt-BR",
                                "transcriptFormat",
                                "TXT",
                                "tags",
                                List.of("flows", "e2e")));
        String content = "Ana: vamos revisar a proposta.\nBruno: fechado, mando ate sexta.";
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
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return mapper.readTree(resp.getBody()).get("id").asText();
    }

    /**
     * Roda a análise SÍNCRONA (auto-dispatch é desligado no profile de teste, igual aos demais
     * ITs). O evento pós-análise → listener async → engine continua assíncrono de verdade — por
     * isso o {@link #awaitExecutions} polla.
     */
    private void runAnalysis(String meetingId, String token) throws Exception {
        JsonNode detail = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        UUID tenantId = UUID.fromString(detail.get("tenantId").asText());
        analysisService.run(UUID.fromString(meetingId), tenantId);
        JsonNode after = authGet("/meetings/" + meetingId, token).read(HttpStatus.OK);
        assertThat(after.get("processingStatus").asText()).isEqualTo("COMPLETED");
    }

    private List<JsonNode> awaitExecutions(String workflowId, String token, int minCount)
            throws Exception {
        for (int i = 0; i < 40; i++) {
            JsonNode executions =
                    authGet("/workflows/" + workflowId + "/executions", token).read(HttpStatus.OK);
            if (executions.size() >= minCount) {
                boolean allTerminal = true;
                for (JsonNode e : executions) {
                    if ("RUNNING".equals(e.get("status").asText())) {
                        allTerminal = false;
                        break;
                    }
                }
                if (allTerminal) {
                    List<JsonNode> result = new java.util.ArrayList<>();
                    executions.forEach(result::add);
                    return result;
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("execução do workflow não apareceu/terminou em 10s");
    }

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name),
                                null)
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken), null)
                .read(HttpStatus.NO_CONTENT);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", pwd), null)
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private RequestExec postJson(String path, Map<String, ?> body, String token) throws Exception {
        return new RequestExec(postJsonRaw(path, body, token));
    }

    private ResponseEntity<String> postJsonRaw(String path, Map<String, ?> body, String token)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return rest.postForEntity(path, entity, String.class);
    }

    private RequestExec putJson(String path, Map<String, ?> body, String token) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.exchange(path, HttpMethod.PUT, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(authGetRaw(path, token));
    }

    private ResponseEntity<String> authGetRaw(String path, String token) {
        return rest.exchange(
                path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
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

    /** Captura e-mails de workflow em memória — prova o envio real sem rede. */
    static class RecordingEmailSender implements EmailSender {
        record Sent(String to, String subject, String html) {}

        final List<Sent> workflowEmails = new CopyOnWriteArrayList<>();

        @Override
        public void sendEmailVerification(String toEmail, String displayName, String link) {}

        @Override
        public void sendPasswordReset(String toEmail, String displayName, String link) {}

        @Override
        public void sendInvitation(
                String toEmail,
                String tenantName,
                String invitedByName,
                String acceptUrl,
                int expiresInDays) {}

        @Override
        public void sendWorkflowNotification(String toEmail, String subject, String htmlBody) {
            workflowEmails.add(new Sent(toEmail, subject, htmlBody));
        }
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        @Primary
        EmailSender recordingEmailSender() {
            return EMAILS;
        }

        /** Stub determinista do worker com action items (HIGH) pro e-mail/condições terem dados. */
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
                        Optional<MeetingGoal> goal) {
                    MeetingAnalysis analysis =
                            MeetingAnalysis.newAnalysis(
                                    meetingId,
                                    tenantId,
                                    "Resumo stub do NORA Flows: proposta revisada será enviada"
                                            + " até sexta-feira.",
                                    Sentiment.NEUTRAL,
                                    List.of("proposta", "renovacao"),
                                    List.of(),
                                    List.of(
                                            ActionItem.fresh(
                                                    "Enviar proposta revisada",
                                                    "[[PESSOA_1]]",
                                                    null,
                                                    Priority.HIGH,
                                                    "mando ate sexta"),
                                            ActionItem.fresh(
                                                    "Agendar follow-up",
                                                    null,
                                                    null,
                                                    Priority.MEDIUM,
                                                    "vamos revisar a proposta")),
                                    List.of(),
                                    List.of(),
                                    "stub-flows-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    2);
                    return AnalysisResult.of(analysis);
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

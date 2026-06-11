package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Risk;
import br.com.nora.api.domain.analysis.RiskCategory;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.analysis.Severity;
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
 * Gatilhos extras do NORA Flows (Fase 4 do GOAL): uma análise COMPLETED com action items e riscos
 * emite, além do evento-âncora, {@code action_item.created} (um por item) e {@code
 * meeting.risk_detected} (só severidade ALTA). Cada gatilho casa apenas os workflows do seu tipo;
 * as execuções e os e-mails comprovam o roteamento.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TriggerEventsIntegrationTest {

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

    @Test
    void analiseDisparaGatilhosDeActionItemERiscoAlto() throws Exception {
        String token = signupAndLogin("triggers@nora.dev", "SenhaForte123", "Triggers");

        // Workflow por gatilho: action_item.created e meeting.risk_detected.
        String actionWorkflowId =
                createWorkflow(
                        token,
                        "Avisar action item",
                        triggerDefinition("action_item.created", "action-dest@nora.dev"),
                        "action_item.created");
        String riskWorkflowId =
                createWorkflow(
                        token,
                        "Alerta de risco",
                        triggerDefinition("meeting.risk_detected", "risk-dest@nora.dev"),
                        "meeting.risk_detected");

        // Análise stub: 2 action items + 1 risco HIGH + 1 risco MEDIUM (não dispara).
        String meetingId = uploadMeeting(token, "Renovação Acme");
        runAnalysis(meetingId, token);

        // action_item.created: uma execução POR item (2 itens no stub).
        List<JsonNode> actionExecutions = awaitExecutions(actionWorkflowId, token, 2);
        assertThat(actionExecutions).hasSize(2);
        for (JsonNode execution : actionExecutions) {
            assertThat(execution.get("status").asText()).isEqualTo("SUCCESS");
            assertThat(execution.get("eventType").asText()).isEqualTo("action_item.created");
            assertThat(execution.get("log").toString())
                    .contains("E-mail enviado para action-dest@nora.dev");
        }

        // meeting.risk_detected: SÓ o risco HIGH dispara — exatamente uma execução.
        List<JsonNode> riskExecutions = awaitExecutions(riskWorkflowId, token, 1);
        // Janela extra: se o risco MEDIUM tivesse emitido evento, a execução já teria aparecido.
        Thread.sleep(750);
        riskExecutions = awaitExecutions(riskWorkflowId, token, 1);
        assertThat(riskExecutions).hasSize(1);
        assertThat(riskExecutions.get(0).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(riskExecutions.get(0).get("eventType").asText())
                .isEqualTo("meeting.risk_detected");

        // E-mails reais capturados: 2 do gatilho de action item + 1 do risco.
        assertThat(EMAILS.workflowEmails).hasSize(3);
        assertThat(
                        EMAILS.workflowEmails.stream()
                                .filter(s -> s.to().equals("action-dest@nora.dev"))
                                .count())
                .isEqualTo(2);
        assertThat(
                        EMAILS.workflowEmails.stream()
                                .filter(s -> s.to().equals("risk-dest@nora.dev"))
                                .count())
                .isEqualTo(1);
    }

    @Test
    void gatilhosExtrasNaoDisparamWorkflowDeOutroTipo() throws Exception {
        String token = signupAndLogin("triggers-iso@nora.dev", "SenhaForte123", "Iso");

        // Workflow do gatilho-âncora: dispara UMA vez, não uma por action item/risco.
        String anchorWorkflowId =
                createWorkflow(
                        token,
                        "Só análise concluída",
                        triggerDefinition("meeting.analysis_completed", "anchor@nora.dev"),
                        "meeting.analysis_completed");

        String meetingId = uploadMeeting(token, "Kickoff Beta");
        runAnalysis(meetingId, token);

        List<JsonNode> executions = awaitExecutions(anchorWorkflowId, token, 1);
        Thread.sleep(750);
        executions = awaitExecutions(anchorWorkflowId, token, 1);
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).get("eventType").asText())
                .isEqualTo("meeting.analysis_completed");
        assertThat(EMAILS.workflowEmails).hasSize(1);
    }

    /* =========================== helpers =========================== */

    private String createWorkflow(
            String token, String name, String definition, String expectedTrigger) throws Exception {
        JsonNode created =
                postJson(
                                "/workflows",
                                Map.of("name", name, "definition", mapper.readTree(definition)),
                                token)
                        .read(HttpStatus.CREATED);
        assertThat(created.get("triggerType").asText()).isEqualTo(expectedTrigger);
        return created.get("id").asText();
    }

    private String triggerDefinition(String triggerType, String to) {
        return """
                {
                  "nodes": [
                    {"id":"t1","kind":"trigger","type":"%s","position":{"x":0,"y":0}},
                    {"id":"a1","kind":"action","type":"send_email",
                     "params":{"to":"%s"},"position":{"x":280,"y":0}}
                  ],
                  "edges": [{"id":"e1","source":"t1","target":"a1"}]
                }
                """
                .formatted(triggerType, to);
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
                                List.of("flows", "triggers")));
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
     * Roda a análise SÍNCRONA (auto-dispatch desligado no profile de teste). Os eventos pós-análise
     * → listeners async → engine continuam assíncronos de verdade — por isso o {@link
     * #awaitExecutions} polla.
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        return new RequestExec(
                rest.exchange(
                        path, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class));
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

        /** Stub determinista: 2 action items + risco HIGH (dispara) + risco MEDIUM (não). */
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
                                    "Resumo stub dos gatilhos extras: cliente pediu desconto e"
                                            + " prazo estendido na renovação.",
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
                                    List.of(
                                            new Risk(
                                                    "Cliente pode não renovar por preço",
                                                    Severity.HIGH,
                                                    RiskCategory.PRICE,
                                                    "o cliente pediu desconto"),
                                            new Risk(
                                                    "Prazo apertado para a proposta",
                                                    Severity.MEDIUM,
                                                    RiskCategory.TIMELINE,
                                                    "mando ate sexta")),
                                    List.of(),
                                    "stub-triggers-1",
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

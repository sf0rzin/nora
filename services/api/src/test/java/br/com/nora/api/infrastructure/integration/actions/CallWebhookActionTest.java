package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.WorkflowEventContext.ActionItemView;
import br.com.nora.api.infrastructure.integration.WebhookHttpClient;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** call_webhook action: payload contract, SSRF guard and failure propagation. */
class CallWebhookActionTest {

    // ── Payload contract ──

    @Test
    @SuppressWarnings("unchecked")
    void payloadCompleto_segueContratoDocumentado() {
        UUID meetingId = UUID.randomUUID();
        WorkflowEventContext ctx = contexto(meetingId, 70, 65);

        Map<String, Object> payload = CallWebhookAction.buildPayload(ctx);

        assertThat(payload)
                .containsEntry("event", "meeting.analysis_completed")
                .containsEntry("sampleData", false);

        Map<String, Object> meeting = (Map<String, Object>) payload.get("meeting");
        assertThat(meeting)
                .containsEntry("id", meetingId.toString())
                .containsEntry("title", "Renovação Acme")
                .containsEntry("url", "http://localhost:3000/meetings/" + meetingId)
                .containsEntry("tags", List.of("renovacao"))
                .containsEntry("summary", "Resumo da reunião.");

        Map<String, Object> stats = (Map<String, Object>) payload.get("stats");
        assertThat(stats)
                .containsEntry("decisions", 2)
                .containsEntry("actionItems", 2)
                .containsEntry("risks", 1);

        Map<String, Object> scores = (Map<String, Object>) payload.get("scores");
        assertThat(scores)
                .containsEntry("productivity", 70)
                .containsEntry("customerConfidence", 65);

        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("actionItems");
        assertThat(items).hasSize(2);
        assertThat(items.get(0))
                .containsEntry("title", "Enviar proposta")
                .containsEntry("assignee", "Ana")
                .containsEntry("dueDate", "2026-06-20");
        // Null fields OMITTED per item (assignee/dueDate with no value).
        assertThat(items.get(1)).containsEntry("title", "Follow-up");
        assertThat(items.get(1)).doesNotContainKeys("assignee", "dueDate");
    }

    @Test
    @SuppressWarnings("unchecked")
    void payloadSemScoresNemMeetingId_omiteCamposNulos() {
        WorkflowEventContext ctx = contextoSample();

        Map<String, Object> payload = CallWebhookAction.buildPayload(ctx);

        assertThat(payload).containsEntry("sampleData", true);
        assertThat(payload).doesNotContainKey("scores");
        Map<String, Object> meeting = (Map<String, Object>) payload.get("meeting");
        assertThat(meeting).doesNotContainKey("id");
        // actionItems is always present — empty list when there are no items.
        assertThat((List<?>) payload.get("actionItems")).isEmpty();
    }

    // ── URL validation / SSRF guard ──

    @Test
    void urlObrigatoria_faltandoFalhaClaro() {
        assertThatThrownBy(() -> CallWebhookAction.requiredUrl(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.url");
    }

    @Test
    void urlHttp_rejeitada() {
        assertThatThrownBy(() -> CallWebhookAction.validateUrl("http://exemplo.com/hook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https://");
    }

    @Test
    void urlMalformada_rejeitada() {
        assertThatThrownBy(() -> CallWebhookAction.validateUrl("isso não é url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enderecosPrivadosLoopbackLinkLocalEMetadata_rejeitados() {
        // Literal IPs don't trigger DNS — deterministic test.
        List<String> bloqueadas =
                List.of(
                        "https://127.0.0.1/hook",
                        "https://10.0.0.8/hook",
                        "https://172.16.0.1/hook",
                        "https://192.168.1.10/hook",
                        "https://169.254.169.254/latest/meta-data",
                        "https://0.0.0.0/hook",
                        "https://[::1]/hook",
                        "https://[fc00::1]/hook",
                        "https://[fdff::1]/hook",
                        "https://localhost/hook");
        for (String url : bloqueadas) {
            assertThatThrownBy(() -> CallWebhookAction.validateUrl(url))
                    .as("deveria bloquear %s", url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("privado");
        }
    }

    @Test
    void urlHttpsPublica_passa() {
        assertThatCode(() -> CallWebhookAction.validateUrl("https://1.1.1.1/hook"))
                .doesNotThrowAnyException();
    }

    // ── Execution (mocked HTTP) ──

    @Test
    void execute_sucessoRetornaStatusEManaHeaders() {
        WebhookHttpClient http = mock(WebhookHttpClient.class);
        when(http.postJson(
                        eq("webhook"),
                        eq("https://1.1.1.1/hook"),
                        eq(
                                Map.of(
                                        "X-Nora-Event",
                                        "meeting.analysis_completed",
                                        "User-Agent",
                                        "NORA-Flows")),
                        any()))
                .thenReturn(204);

        String result =
                new CallWebhookAction(http)
                        .execute(
                                contexto(UUID.randomUUID(), 70, 65),
                                Map.of("url", "https://1.1.1.1/hook"));

        assertThat(result).isEqualTo("Webhook chamado: 204");
    }

    @Test
    void execute_falhaDoEndpointPropaga() {
        WebhookHttpClient http = mock(WebhookHttpClient.class);
        when(http.postJson(anyString(), anyString(), anyMap(), any()))
                .thenThrow(new IntegrationException.ProviderError("webhook", "respondeu HTTP 500"));

        assertThatThrownBy(
                        () ->
                                new CallWebhookAction(http)
                                        .execute(
                                                contexto(UUID.randomUUID(), 70, 65),
                                                Map.of("url", "https://1.1.1.1/hook")))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("500");
    }

    // ── Fixtures ──

    private static WorkflowEventContext contexto(
            UUID meetingId, Integer productivity, Integer confidence) {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                meetingId,
                "Renovação Acme",
                List.of("renovacao"),
                "Resumo da reunião.",
                "Resumo da reunião.",
                2,
                2,
                1,
                List.of(
                        new ActionItemView(
                                "Enviar proposta", "Ana", "HIGH", LocalDate.of(2026, 6, 20)),
                        new ActionItemView("Follow-up", null, "MEDIUM", null)),
                productivity,
                confidence,
                "http://localhost:3000/meetings/" + meetingId,
                OffsetDateTime.now(ZoneOffset.UTC),
                false);
    }

    private static WorkflowEventContext contextoSample() {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                null,
                "Reunião de exemplo",
                List.of(),
                null,
                null,
                0,
                0,
                0,
                List.of(),
                null,
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                true);
    }
}

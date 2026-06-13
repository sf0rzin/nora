package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.MicrosoftGraphClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ação outlook_send_email: espelho da gmail_send_email com a conta Microsoft conectada. */
class OutlookSendEmailActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingGraph graph = new RecordingGraph();
    private final OutlookSendEmailAction action = new OutlookSendEmailAction(integrations, graph);

    @Test
    void enviaComAssuntoDefaultECorpoRelatorio() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.MICROSOFT)))
                .thenReturn("ms_token");
        WorkflowEventContext ctx = TestContexts.context("Renovação Acme", "Resumo.", List.of());

        String result = action.execute(ctx, Map.of("to", "cliente@acme.com"));

        assertThat(result).isEqualTo("E-mail enviado via Outlook para cliente@acme.com");
        assertThat(graph.mails).hasSize(1);
        RecordingGraph.Mail mail = graph.mails.get(0);
        assertThat(mail.token()).isEqualTo("ms_token");
        assertThat(mail.to()).isEqualTo("cliente@acme.com");
        assertThat(mail.subject()).isEqualTo("NORA — Reunião analisada: Renovação Acme");
        // Corpo HTML do relatório-resumo compartilhado (WorkflowActionTemplates).
        assertThat(mail.html()).contains("Renovação Acme").contains("NORA");
    }

    @Test
    void assuntoECorpoCustomComPlaceholders() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.MICROSOFT)))
                .thenReturn("ms_token");
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());

        action.execute(
                ctx,
                Map.of(
                        "to", "a@b.c",
                        "subject", "Pós: {{meeting.title}}",
                        "body", "Resumo: {{meeting.summary}}"));

        assertThat(graph.mails.get(0).subject()).isEqualTo("Pós: Kickoff");
        assertThat(graph.mails.get(0).html()).contains("Resumo.");
    }

    @Test
    void destinatarioObrigatorio() {
        assertThatThrownBy(
                        () -> action.execute(TestContexts.context("R", "S", List.of()), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.to");
        assertThat(graph.mails).isEmpty();
    }

    /** Captura envios em memória (substitui as chamadas HTTP reais ao Graph). */
    static class RecordingGraph extends MicrosoftGraphClient {
        record Mail(String token, String to, String subject, String html) {}

        final List<Mail> mails = new ArrayList<>();

        RecordingGraph() {
            super(new ObjectMapper());
        }

        @Override
        public void sendMail(String accessToken, String to, String subject, String htmlBody) {
            mails.add(new Mail(accessToken, to, subject, htmlBody));
        }
    }
}

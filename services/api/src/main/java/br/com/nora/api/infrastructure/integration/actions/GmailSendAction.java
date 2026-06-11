package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.GoogleWorkspaceClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Enviar e-mail via Gmail" do NORA Flows — envia PELA CONTA GOOGLE conectada do tenant (OAuth
 * real), diferente da {@code send_email} (Resend, remetente NORA). Params: {@code to}
 * (obrigatório), {@code subject}/{@code body} opcionais com os mesmos placeholders da send_email.
 * Sem conexão Google → exceção clara no log da execução (nunca finge sucesso).
 */
@Component
public class GmailSendAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final GoogleWorkspaceClient google;

    public GmailSendAction(IntegrationService integrations, GoogleWorkspaceClient google) {
        this.integrations = integrations;
        this.google = google;
    }

    @Override
    public String type() {
        return "gmail_send_email";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String to = WorkflowActionTemplates.requiredEmail(params, "to");
        String subject =
                WorkflowActionTemplates.subjectOrDefault(
                        params, "NORA — Reunião analisada: {{meeting.title}}", ctx);
        String html = WorkflowActionTemplates.bodyOrDefault(params, ctx);
        String accessToken = integrations.validGoogleAccessToken(ctx.tenantId());
        google.sendGmail(accessToken, to, subject, html);
        return "E-mail enviado via Gmail para " + to;
    }
}

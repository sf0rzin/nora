package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.GoogleWorkspaceClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Send email via Gmail" action — sends FROM the tenant's connected GOOGLE ACCOUNT
 * (real OAuth), unlike {@code send_email} (Resend, NORA as sender). Params: {@code to} (required),
 * optional {@code subject}/{@code body} with the same placeholders as send_email. No Google
 * connection → clear exception in the run log (never fakes success).
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

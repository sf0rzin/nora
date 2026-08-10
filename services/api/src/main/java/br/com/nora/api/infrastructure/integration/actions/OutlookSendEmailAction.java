package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.MicrosoftGraphClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Send email via Outlook" action — sends FROM the tenant's connected MICROSOFT
 * ACCOUNT (OAuth with refresh, wave 2), mirror of {@code gmail_send_email}. Params: {@code to}
 * (required), optional {@code subject}/{@code body} with the same placeholders as the e-mail
 * actions. No Microsoft connection → clear exception in the run log (never fakes success).
 */
@Component
public class OutlookSendEmailAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final MicrosoftGraphClient graph;

    public OutlookSendEmailAction(IntegrationService integrations, MicrosoftGraphClient graph) {
        this.integrations = integrations;
        this.graph = graph;
    }

    @Override
    public String type() {
        return "outlook_send_email";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String to = WorkflowActionTemplates.requiredEmail(params, "to");
        String subject =
                WorkflowActionTemplates.subjectOrDefault(
                        params, "NORA — Reunião analisada: {{meeting.title}}", ctx);
        String html = WorkflowActionTemplates.bodyOrDefault(params, ctx);
        String accessToken =
                integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.MICROSOFT);
        graph.sendMail(accessToken, to, subject, html);
        return "E-mail enviado via Outlook para " + to;
    }
}

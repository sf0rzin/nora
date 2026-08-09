package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Send e-mail" action — REAL e-mail via the {@link EmailSender} port (Resend in
 * production, NORA sender). Params: {@code to} (required, validated on save), {@code subject} and
 * {@code body} optional with placeholders (see {@link WorkflowActionTemplates}). Without
 * subject/body, it builds a default HTML summary report of the meeting.
 *
 * <p>A send failure PROPAGATES ({@link ActionExecutor} contract) — the engine records it in the log
 * and the execution ends up FAILED. It never fakes success.
 */
@Component
public class SendEmailAction implements ActionExecutor {

    private final EmailSender emails;

    public SendEmailAction(EmailSender emails) {
        this.emails = emails;
    }

    @Override
    public String type() {
        return "send_email";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String to = WorkflowActionTemplates.requiredEmail(params, "to");
        String subject =
                WorkflowActionTemplates.subjectOrDefault(
                        params, "NORA — Reunião analisada: {{meeting.title}}", ctx);
        String html = WorkflowActionTemplates.bodyOrDefault(params, ctx);
        emails.sendWorkflowNotification(to, subject, html);
        return "E-mail enviado para " + to;
    }
}

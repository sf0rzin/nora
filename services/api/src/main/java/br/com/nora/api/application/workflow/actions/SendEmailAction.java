package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Enviar e-mail" do NORA Flows — e-mail REAL via a porta {@link EmailSender} (Resend em
 * produção, remetente NORA). Params: {@code to} (obrigatório, validado no save), {@code subject} e
 * {@code body} opcionais com placeholders (ver {@link WorkflowActionTemplates}). Sem subject/body,
 * monta um relatório-resumo padrão da reunião em HTML.
 *
 * <p>Falha de envio PROPAGA (contrato do {@link ActionExecutor}) — o engine registra no log e a
 * execução fica FAILED. Nunca finge sucesso.
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

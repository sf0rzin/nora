package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.ports.EmailSender;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Enviar e-mail" do NORA Flows — e-mail REAL via a porta {@link EmailSender} (Resend em
 * produção). Params: {@code to} (obrigatório, validado no save), {@code subject} e {@code body}
 * opcionais com placeholders ({{meeting.title}}, {{meeting.summary}}, {{meeting.url}},
 * {{productivity.score}}, {{confidence.score}}). Sem subject/body, monta um relatório-resumo padrão
 * da reunião em HTML.
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
        String to = stringParam(params, "to");
        if (to == null || !to.contains("@")) {
            throw new IllegalArgumentException("destinatário inválido em params.to");
        }
        String subject = stringParam(params, "subject");
        if (subject == null || subject.isBlank()) {
            subject = "NORA — Reunião analisada: {{meeting.title}}";
        }
        subject = applyPlaceholders(subject, ctx, false);

        String customBody = stringParam(params, "body");
        String html =
                customBody == null || customBody.isBlank()
                        ? defaultBody(ctx)
                        : customBodyHtml(customBody, ctx);

        emails.sendWorkflowNotification(to, subject, html);
        return "E-mail enviado para " + to;
    }

    private String customBodyHtml(String body, WorkflowEventContext ctx) {
        String content = applyPlaceholders(body, ctx, true);
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;color:#15171a;\">");
        for (String paragraph : content.split("\\n\\n+")) {
            sb.append("<p>").append(paragraph.replace("\n", "<br>")).append("</p>");
        }
        appendFooter(sb, ctx);
        sb.append("</div>");
        return sb.toString();
    }

    private String defaultBody(WorkflowEventContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:Arial,Helvetica,sans-serif;color:#15171a;")
                .append("max-width:560px;\">");
        if (ctx.sampleData()) {
            sb.append(
                    "<p style=\"background:#fff7e6;border:1px solid #f0d9a8;border-radius:6px;"
                            + "padding:8px 12px;font-size:13px;\">Execução de teste com dados de"
                            + " exemplo — o tenant ainda não tem reunião analisada.</p>");
        }
        sb.append("<p style=\"font-size:13px;letter-spacing:0.08em;text-transform:uppercase;")
                .append("color:#6e7178;margin-bottom:4px;\">NORA Flows · Reunião analisada</p>");
        sb.append("<h2 style=\"margin:0 0 12px;\">")
                .append(escape(ctx.meetingTitle()))
                .append("</h2>");
        if (ctx.summary() != null) {
            sb.append("<p style=\"line-height:1.5;\">")
                    .append(escape(ctx.summary()))
                    .append("</p>");
        }
        sb.append("<table style=\"border-collapse:collapse;margin:16px 0;font-size:14px;\">");
        appendStat(sb, "Decisões", String.valueOf(ctx.decisionsCount()));
        appendStat(sb, "Action items", String.valueOf(ctx.actionItemsCount()));
        appendStat(sb, "Riscos", String.valueOf(ctx.risksCount()));
        if (ctx.productivityScore() != null) {
            appendStat(sb, "Productivity Score", ctx.productivityScore() + "/100");
        }
        if (ctx.customerConfidenceScore() != null) {
            appendStat(sb, "Customer Confidence", ctx.customerConfidenceScore() + "/100");
        }
        sb.append("</table>");
        if (!ctx.actionItems().isEmpty()) {
            sb.append("<p style=\"margin-bottom:4px;\"><strong>Próximos passos:</strong></p><ul>");
            for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
                sb.append("<li>")
                        .append(escape(item.title()))
                        .append(
                                item.assignee() == null || item.assignee().isBlank()
                                        ? ""
                                        : " — " + escape(item.assignee()))
                        .append("</li>");
            }
            sb.append("</ul>");
        }
        appendFooter(sb, ctx);
        sb.append("</div>");
        return sb.toString();
    }

    private void appendStat(StringBuilder sb, String label, String value) {
        sb.append("<tr><td style=\"padding:2px 16px 2px 0;color:#6e7178;\">")
                .append(label)
                .append("</td><td style=\"padding:2px 0;\"><strong>")
                .append(escape(value))
                .append("</strong></td></tr>");
    }

    private void appendFooter(StringBuilder sb, WorkflowEventContext ctx) {
        if (ctx.meetingUrl() != null) {
            sb.append("<p style=\"margin-top:20px;\"><a href=\"")
                    .append(ctx.meetingUrl())
                    .append("\" style=\"display:inline-block;padding:10px 20px;background:#111;")
                    .append("color:#fff;text-decoration:none;border-radius:6px;\">")
                    .append("Abrir no NORA</a></p>");
        }
        sb.append(
                "<p style=\"font-size:12px;color:#6e7178;margin-top:24px;\">Enviado"
                        + " automaticamente por um fluxo do NORA Flows.</p>");
    }

    private String applyPlaceholders(String template, WorkflowEventContext ctx, boolean escape) {
        String result = template;
        result = replace(result, "{{meeting.title}}", ctx.meetingTitle(), escape);
        result = replace(result, "{{meeting.summary}}", ctx.summary(), escape);
        result = replace(result, "{{meeting.url}}", ctx.meetingUrl(), false);
        result =
                replace(
                        result,
                        "{{meeting.tags}}",
                        ctx.tags() == null ? "" : String.join(", ", ctx.tags()),
                        escape);
        result =
                replace(
                        result,
                        "{{productivity.score}}",
                        ctx.productivityScore() == null
                                ? "—"
                                : String.valueOf(ctx.productivityScore()),
                        false);
        result =
                replace(
                        result,
                        "{{confidence.score}}",
                        ctx.customerConfidenceScore() == null
                                ? "—"
                                : String.valueOf(ctx.customerConfidenceScore()),
                        false);
        return result;
    }

    private static String replace(String template, String key, String value, boolean escape) {
        String safe = value == null ? "" : (escape ? escape(value) : value);
        return template.replace(key, safe);
    }

    private static String escape(String s) {
        return s == null
                ? ""
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

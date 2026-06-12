package br.com.nora.api.application.workflow.actions;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.util.Map;

/**
 * Renderização compartilhada das ações de e-mail do Flows (send_email via Resend e gmail_send_email
 * via conta do usuário): placeholders ({{meeting.title}}, {{meeting.summary}}, {{meeting.url}},
 * {{meeting.tags}}, {{productivity.score}}, {{confidence.score}}), corpo padrão (relatório-resumo
 * da reunião em HTML) e validação de destinatário.
 */
public final class WorkflowActionTemplates {

    private WorkflowActionTemplates() {}

    public static String requiredEmail(Map<String, Object> params, String key) {
        String value = stringParam(params, key);
        if (value == null || !value.contains("@")) {
            throw new IllegalArgumentException("destinatário inválido em params." + key);
        }
        return value.trim();
    }

    public static String subjectOrDefault(
            Map<String, Object> params, String defaultTemplate, WorkflowEventContext ctx) {
        String subject = stringParam(params, "subject");
        if (subject == null || subject.isBlank()) {
            subject = defaultTemplate;
        }
        return applyPlaceholders(subject, ctx, false);
    }

    /** Corpo HTML: o {@code params.body} custom (com placeholders) ou o relatório-resumo padrão. */
    public static String bodyOrDefault(Map<String, Object> params, WorkflowEventContext ctx) {
        String customBody = stringParam(params, "body");
        return customBody == null || customBody.isBlank()
                ? defaultBody(ctx)
                : customBodyHtml(customBody, ctx);
    }

    // ---- Layout do e-mail ----------------------------------------------------------------
    // Tudo inline (cliente de e-mail ignora <style>) e com tabela externa pra compatibilidade.
    // Paleta espelha o Core: canvas #f4f3f1, cartao branco, ink #15171a, muted #6e7178.

    private static final String FONT_STACK = "'DM Sans','Segoe UI',-apple-system,Arial,sans-serif";

    private static String customBodyHtml(String body, WorkflowEventContext ctx) {
        // Placeholders entram crus e o conjunto (template do usuário + conteúdo da reunião)
        // é renderizado como Markdown — o escape acontece dentro do renderer.
        String content = MarkdownLite.render(applyPlaceholders(body, ctx, false));
        return frame(ctx, content, true);
    }

    private static String defaultBody(WorkflowEventContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("<h1 style=\"font-size:20px;font-weight:600;letter-spacing:-0.01em;")
                .append("margin:0 0 14px;\">")
                .append(escape(ctx.meetingTitle()))
                .append("</h1>");
        if (ctx.summary() != null) {
            sb.append("<div style=\"font-size:14px;color:#2a2d31;\">")
                    .append(MarkdownLite.render(ctx.summary()))
                    .append("</div>");
        }
        appendStats(sb, ctx);
        if (!ctx.actionItems().isEmpty()) {
            sb.append("<p style=\"font-size:13px;font-weight:600;margin:18px 0 6px;\">")
                    .append("Próximos passos</p>")
                    .append("<ul style=\"margin:0;padding-left:22px;font-size:14px;\">");
            for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
                sb.append("<li style=\"margin:0 0 5px;line-height:1.55;\">")
                        .append(escape(item.title()))
                        .append(
                                item.assignee() == null || item.assignee().isBlank()
                                        ? ""
                                        : " <span style=\"color:#6e7178;\">— "
                                                + escape(item.assignee())
                                                + "</span>")
                        .append("</li>");
            }
            sb.append("</ul>");
        }
        return frame(ctx, sb.toString(), true);
    }

    /** Linha de métricas: valor em destaque + rótulo discreto, em tabela (e-mail-safe). */
    private static void appendStats(StringBuilder sb, WorkflowEventContext ctx) {
        sb.append("<table role=\"presentation\" style=\"border-collapse:separate;")
                .append("border-spacing:8px 0;margin:18px -8px 4px;\"><tr>");
        statCell(sb, String.valueOf(ctx.decisionsCount()), "Decisões");
        statCell(sb, String.valueOf(ctx.actionItemsCount()), "Action items");
        statCell(sb, String.valueOf(ctx.risksCount()), "Riscos");
        if (ctx.productivityScore() != null) {
            statCell(
                    sb,
                    ctx.productivityScore() + "<span style=\"font-size:11px;\">/100</span>",
                    "Productivity");
        }
        if (ctx.customerConfidenceScore() != null) {
            statCell(
                    sb,
                    ctx.customerConfidenceScore() + "<span style=\"font-size:11px;\">/100</span>",
                    "Confidence");
        }
        sb.append("</tr></table>");
    }

    private static void statCell(StringBuilder sb, String value, String label) {
        sb.append("<td style=\"background:#f7f6f4;border:1px solid #eceae6;border-radius:8px;")
                .append("padding:10px 14px;text-align:center;\">")
                .append("<div style=\"font-size:18px;font-weight:600;letter-spacing:-0.01em;\">")
                .append(value)
                .append("</div><div style=\"font-size:11px;color:#6e7178;margin-top:2px;\">")
                .append(label)
                .append("</div></td>");
    }

    /** Moldura comum: fundo, cabeçalho NORA, cartão branco com o conteúdo, CTA e rodapé. */
    private static String frame(WorkflowEventContext ctx, String innerHtml, boolean withCta) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"background:#f4f3f1;padding:28px 16px;\">")
                .append("<div style=\"max-width:600px;margin:0 auto;font-family:")
                .append(FONT_STACK)
                .append(";color:#15171a;\">");

        // Cabeçalho: wordmark + contexto.
        sb.append("<div style=\"padding:0 6px 14px;\">")
                .append("<span style=\"font-size:16px;font-weight:700;letter-spacing:-0.02em;\">")
                .append("NORA</span>")
                .append("<span style=\"font-size:11px;color:#6e7178;letter-spacing:0.09em;")
                .append("text-transform:uppercase;margin-left:12px;\">Reunião analisada</span>")
                .append("</div>");

        // Cartão.
        sb.append("<div style=\"background:#ffffff;border:1px solid #e9e7e3;border-radius:12px;")
                .append("padding:28px;\">");
        if (ctx.sampleData()) {
            sb.append("<p style=\"background:#fdf6e7;border:1px solid #f0e2bb;border-radius:8px;")
                    .append("padding:9px 14px;font-size:12.5px;color:#7a6427;margin:0 0 18px;\">")
                    .append("Execução de teste com dados de exemplo.</p>");
        }
        sb.append(innerHtml);
        if (withCta && ctx.meetingUrl() != null) {
            sb.append("<p style=\"margin:24px 0 0;\"><a href=\"")
                    .append(ctx.meetingUrl())
                    .append("\" style=\"display:inline-block;padding:11px 22px;background:#15171a;")
                    .append("color:#ffffff;text-decoration:none;border-radius:8px;font-size:14px;")
                    .append("font-weight:500;\">Abrir no NORA</a></p>");
        }
        sb.append("</div>");

        // Rodapé.
        sb.append("<p style=\"font-size:11.5px;color:#9a9c9f;margin:16px 6px 0;\">")
                .append("Enviado por um fluxo do NORA Flows · ")
                .append(
                        "<a href=\"https://nora.systems\" style=\"color:#6e7178;\">nora.systems</a>")
                .append("</p>");

        sb.append("</div></div>");
        return sb.toString();
    }

    public static String applyPlaceholders(
            String template, WorkflowEventContext ctx, boolean escape) {
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

    public static String escape(String s) {
        return s == null
                ? ""
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }

    public static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

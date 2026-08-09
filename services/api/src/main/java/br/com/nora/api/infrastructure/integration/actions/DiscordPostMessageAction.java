package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.WebhookHttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Avisar no Discord" action — posts an embed with the meeting summary to a channel via
 * a Discord webhook, with no global credential (the channel webhook already carries the
 * authorization). Required param: {@code webhookUrl}, which MUST start with {@code
 * https://discord.com/api/webhooks/} or {@code https://discordapp.com/api/webhooks/} — which also
 * rules out SSRF by construction.
 *
 * <p>Embed: title = meeting title, description = summary (truncated at ~1500 chars), inline fields
 * with Decisões/Action items/Riscos (+ Productivity and Confidence when present), a "Próximos
 * passos" field with up to 5 items, NORA color (0x4EC4D8), footer "NORA Flows" and url pointing at
 * the meeting in NORA. Webhook username: "NORA".
 *
 * <p>Success = 2xx (Discord answers 204). A failure PROPAGATES ({@link ActionExecutor} contract) —
 * the engine writes FAILED in the log. It never fakes success.
 */
@Component
public class DiscordPostMessageAction implements ActionExecutor {

    /** Summary cap in the embed — Discord accepts 4096, but chat calls for a compact message. */
    static final int DESCRIPTION_MAX = 1500;

    /** Embed title: Discord's hard limit. */
    static final int TITLE_MAX = 256;

    /** Items shown in the "Próximos passos" field. */
    static final int MAX_ACTION_ITEMS = 5;

    /** NORA tone 0x4EC4D8 in decimal — the color format Discord's API expects. */
    static final int COR_NORA = 0x4EC4D8; // 5162200

    static final List<String> PREFIXOS_VALIDOS =
            List.of("https://discord.com/api/webhooks/", "https://discordapp.com/api/webhooks/");

    private final WebhookHttpClient http;

    public DiscordPostMessageAction(WebhookHttpClient http) {
        this.http = http;
    }

    @Override
    public String type() {
        return "discord_post_message";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String webhookUrl = requiredWebhookUrl(params);
        int status =
                http.postJson(
                        "discord",
                        webhookUrl,
                        Map.of("User-Agent", "NORA-Flows"),
                        buildPayload(ctx));
        return "Mensagem enviada no Discord (HTTP " + status + ")";
    }

    static String requiredWebhookUrl(Map<String, Object> params) {
        String url = WorkflowActionTemplates.stringParam(params, "webhookUrl");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(
                    "URL do webhook obrigatória em params.webhookUrl (crie em Configurações do"
                            + " canal → Integrações → Webhooks)");
        }
        String trimmed = url.trim();
        if (PREFIXOS_VALIDOS.stream().noneMatch(trimmed::startsWith)) {
            throw new IllegalArgumentException(
                    "URL de webhook do Discord inválida — deve começar com"
                            + " https://discord.com/api/webhooks/");
        }
        return trimmed;
    }

    /** Discord webhook payload: username "NORA" + one embed with the meeting summary. */
    static Map<String, Object> buildPayload(WorkflowEventContext ctx) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", truncar(ctx.meetingTitle(), TITLE_MAX));
        if (ctx.summary() != null && !ctx.summary().isBlank()) {
            embed.put("description", truncar(ctx.summary(), DESCRIPTION_MAX));
        }
        if (ctx.meetingUrl() != null) {
            embed.put("url", ctx.meetingUrl());
        }
        embed.put("color", COR_NORA);
        embed.put("fields", buildFields(ctx));
        embed.put("footer", Map.of("text", "NORA Flows"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", "NORA");
        payload.put("embeds", List.of(embed));
        return payload;
    }

    private static List<Map<String, Object>> buildFields(WorkflowEventContext ctx) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Decisões", String.valueOf(ctx.decisionsCount()), true));
        fields.add(field("Action items", String.valueOf(ctx.actionItemsCount()), true));
        fields.add(field("Riscos", String.valueOf(ctx.risksCount()), true));
        if (ctx.productivityScore() != null) {
            fields.add(field("Productivity", ctx.productivityScore() + "/100", true));
        }
        if (ctx.customerConfidenceScore() != null) {
            fields.add(field("Confidence", ctx.customerConfidenceScore() + "/100", true));
        }
        if (!ctx.actionItems().isEmpty()) {
            fields.add(field("Próximos passos", proximosPassos(ctx), false));
        }
        return fields;
    }

    /** Up to {@value MAX_ACTION_ITEMS} items "• title — assignee" (assignee when there is one). */
    private static String proximosPassos(WorkflowEventContext ctx) {
        StringBuilder sb = new StringBuilder();
        List<WorkflowEventContext.ActionItemView> items = ctx.actionItems();
        for (int i = 0; i < Math.min(items.size(), MAX_ACTION_ITEMS); i++) {
            WorkflowEventContext.ActionItemView item = items.get(i);
            if (i > 0) {
                sb.append("\n");
            }
            sb.append("• ").append(item.title());
            if (item.assignee() != null && !item.assignee().isBlank()) {
                sb.append(" — ").append(item.assignee());
            }
        }
        // Discord's hard limit for a field value.
        return truncar(sb.toString(), 1024);
    }

    private static Map<String, Object> field(String name, String value, boolean inline) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("value", value);
        field.put("inline", inline);
        return field;
    }

    static String truncar(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 1) + "…";
    }
}

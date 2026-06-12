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
 * Ação "Avisar no Discord" do NORA Flows — posta um embed com o resumo da reunião num canal via
 * webhook do Discord, sem credencial global (o webhook do canal já carrega a autorização). Param
 * obrigatório: {@code webhookUrl}, que DEVE começar com {@code https://discord.com/api/webhooks/}
 * ou {@code https://discordapp.com/api/webhooks/} — o que também elimina SSRF por construção.
 *
 * <p>Embed: title = título da reunião, description = resumo (truncado em ~1500 chars), fields
 * inline com Decisões/Action items/Riscos (+ Productivity e Confidence quando existem), field
 * "Próximos passos" com até 5 itens, cor NORA (0x4EC4D8), footer "NORA Flows" e url apontando pra
 * reunião no NORA. Username do webhook: "NORA".
 *
 * <p>Sucesso = 2xx (o Discord responde 204). Falha PROPAGA (contrato do {@link ActionExecutor}) — o
 * engine grava FAILED no log. Nunca finge sucesso.
 */
@Component
public class DiscordPostMessageAction implements ActionExecutor {

    /** Limite do resumo no embed — Discord aceita 4096, mas chat pede mensagem compacta. */
    static final int DESCRIPTION_MAX = 1500;

    /** Título de embed: limite hard do Discord. */
    static final int TITLE_MAX = 256;

    /** Itens exibidos no field "Próximos passos". */
    static final int MAX_ACTION_ITEMS = 5;

    /** Tom NORA 0x4EC4D8 em decimal — formato de cor que a API do Discord espera. */
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

    /** Payload de webhook do Discord: username "NORA" + um embed com o resumo da reunião. */
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

    /** Até {@value MAX_ACTION_ITEMS} itens "• título — responsável" (responsável quando há). */
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
        // Limite hard do Discord pra value de field.
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

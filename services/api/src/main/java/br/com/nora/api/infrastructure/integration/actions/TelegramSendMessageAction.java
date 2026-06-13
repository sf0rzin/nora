package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TelegramBotHttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Avisar no Telegram" do NORA Flows — mensagem REAL no chat pareado do tenant (bot do app; o
 * chat_id é o "token" da conexão, salvo no pareamento por código). Sem params: o texto é um resumo
 * formatado em HTML do Telegram — título em {@code <b>}, contagens da análise e até 5 próximos
 * passos + link da reunião.
 *
 * <p>Falha PROPAGA (contrato do {@link ActionExecutor}) — ex.: usuário bloqueou o bot vira erro
 * claro no log da execução. Nunca finge sucesso.
 */
@Component
public class TelegramSendMessageAction implements ActionExecutor {

    /** Máximo de próximos passos listados na mensagem (chat, não relatório). */
    static final int MAX_ITEMS = 5;

    private final IntegrationService integrations;
    private final TelegramBotHttpClient telegram;

    public TelegramSendMessageAction(
            IntegrationService integrations, TelegramBotHttpClient telegram) {
        this.integrations = integrations;
        this.telegram = telegram;
    }

    @Override
    public String type() {
        return "telegram_send_message";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String chatId = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.TELEGRAM);
        telegram.sendMessageHtml(chatId, buildHtml(ctx));
        return "Mensagem enviada no Telegram";
    }

    /** Resumo em HTML do Telegram (só tags suportadas: b/i/a; conteúdo dinâmico escapado). */
    static String buildHtml(WorkflowEventContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>").append(escape(ctx.meetingTitle())).append("</b>\n");
        sb.append("Reunião analisada pelo NORA — ")
                .append(ctx.decisionsCount())
                .append(plural(ctx.decisionsCount(), " decisão", " decisões"))
                .append(" · ")
                .append(ctx.actionItemsCount())
                .append(" action items · ")
                .append(ctx.risksCount())
                .append(plural(ctx.risksCount(), " risco", " riscos"));
        if (ctx.productivityScore() != null) {
            sb.append(" · Productivity ").append(ctx.productivityScore()).append("/100");
        }
        sb.append("\n");

        List<WorkflowEventContext.ActionItemView> items = ctx.actionItems();
        if (!items.isEmpty()) {
            sb.append("\n<b>Próximos passos</b>\n");
            for (WorkflowEventContext.ActionItemView item :
                    items.subList(0, Math.min(items.size(), MAX_ITEMS))) {
                sb.append("• ").append(escape(item.title()));
                if (item.assignee() != null && !item.assignee().isBlank()) {
                    sb.append(" — ").append(escape(item.assignee()));
                }
                sb.append("\n");
            }
            if (items.size() > MAX_ITEMS) {
                sb.append("… e mais ").append(items.size() - MAX_ITEMS).append("\n");
            }
        }

        if (ctx.meetingUrl() != null) {
            sb.append("\n").append(ctx.meetingUrl());
        }
        return sb.toString().strip();
    }

    private static String plural(int count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

    /** Escape mínimo exigido pelo parse_mode HTML do Telegram. */
    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

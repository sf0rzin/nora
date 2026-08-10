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
 * NORA Flows "Notify on Telegram" action — REAL message in the tenant's paired chat (the app bot;
 * the chat_id is the connection "token", saved during code pairing). No params: the text is a
 * summary formatted in Telegram HTML — title in {@code <b>}, analysis counts and up to 5 next steps
 * + the meeting link.
 *
 * <p>A failure PROPAGATES ({@link ActionExecutor} contract) — e.g. a user who blocked the bot turns
 * into a clear error in the run log. It never fakes success.
 */
@Component
public class TelegramSendMessageAction implements ActionExecutor {

    /** Maximum next steps listed in the message (chat, not a report). */
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

    /** Summary in Telegram HTML (supported tags only: b/i/a; dynamic content escaped). */
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

    /** Minimal escaping required by Telegram's HTML parse_mode. */
    static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

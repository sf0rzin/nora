package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.SlackClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Postar no Slack" action — REAL message in a channel of the connected workspace (bot
 * token via OAuth v2). Params: {@code channel} (required, e.g. {@code #vendas}; validated on save)
 * and an optional {@code text} with the same placeholders as the e-mail actions. With no text, it
 * posts a compact meeting summary with a link (Slack is chat, not a report).
 *
 * <p>A failure PROPAGATES ({@link ActionExecutor} contract) — e.g. a bot outside the channel turns
 * into a clear error in the run log, with /invite guidance. It never fakes success.
 */
@Component
public class SlackPostMessageAction implements ActionExecutor {

    /** Maximum size of the default text before the link (cut with an ellipsis). */
    static final int DEFAULT_TEXT_MAX = 400;

    private final IntegrationService integrations;
    private final SlackClient slack;

    public SlackPostMessageAction(IntegrationService integrations, SlackClient slack) {
        this.integrations = integrations;
        this.slack = slack;
    }

    @Override
    public String type() {
        return "slack_post_message";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String channel = requiredChannel(params);
        String text = renderText(params, ctx);
        String botToken = integrations.validSlackBotToken(ctx.tenantId());
        slack.postMessage(botToken, channel, text);
        return "Mensagem enviada no Slack para " + channel;
    }

    /** Custom text (placeholders applied) or the compact default. */
    static String renderText(Map<String, Object> params, WorkflowEventContext ctx) {
        String custom = WorkflowActionTemplates.stringParam(params, "text");
        return custom == null || custom.isBlank()
                ? defaultText(ctx)
                : WorkflowActionTemplates.applyPlaceholders(custom, ctx, false);
    }

    /** Default: title + summary truncated to ~400 chars + meeting link in NORA. */
    static String defaultText(WorkflowEventContext ctx) {
        String text =
                WorkflowActionTemplates.applyPlaceholders(
                        "*{{meeting.title}}* analisada pelo NORA — resumo: {{meeting.summary}}",
                        ctx,
                        false);
        if (text.length() > DEFAULT_TEXT_MAX) {
            text = text.substring(0, DEFAULT_TEXT_MAX - 1) + "…";
        }
        if (ctx.meetingUrl() != null) {
            text += "\n" + ctx.meetingUrl();
        }
        return text;
    }

    static String requiredChannel(Map<String, Object> params) {
        String channel = WorkflowActionTemplates.stringParam(params, "channel");
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException(
                    "canal obrigatório em params.channel (ex.: #vendas)");
        }
        return channel.trim();
    }
}

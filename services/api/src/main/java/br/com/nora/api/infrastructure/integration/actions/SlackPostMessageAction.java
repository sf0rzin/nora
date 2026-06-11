package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.SlackClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Postar no Slack" do NORA Flows — mensagem REAL num canal da workspace conectada (bot token
 * via OAuth v2). Params: {@code channel} (obrigatório, ex.: {@code #vendas}; validado no save) e
 * {@code text} opcional com os mesmos placeholders das ações de e-mail. Sem text, posta um resumo
 * compacto da reunião com link (Slack é chat, não relatório).
 *
 * <p>Falha PROPAGA (contrato do {@link ActionExecutor}) — ex.: bot fora do canal vira erro claro no
 * log da execução, com orientação de /invite. Nunca finge sucesso.
 */
@Component
public class SlackPostMessageAction implements ActionExecutor {

    /** Tamanho máximo do texto default antes do link (corte com reticências). */
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

    /** Texto custom (placeholders aplicados) ou o default compacto. */
    static String renderText(Map<String, Object> params, WorkflowEventContext ctx) {
        String custom = WorkflowActionTemplates.stringParam(params, "text");
        return custom == null || custom.isBlank()
                ? defaultText(ctx)
                : WorkflowActionTemplates.applyPlaceholders(custom, ctx, false);
    }

    /** Default: título + resumo truncado a ~400 chars + link da reunião no NORA. */
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

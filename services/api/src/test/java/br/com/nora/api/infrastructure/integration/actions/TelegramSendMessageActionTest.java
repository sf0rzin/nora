package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TelegramBotHttpClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * telegram_send_message action: HTML summary in the paired chat (chat_id is the connection "token")
 * — title in b, counts and up to 5 next steps.
 */
class TelegramSendMessageActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingTelegram telegram = new RecordingTelegram();
    private final TelegramSendMessageAction action =
            new TelegramSendMessageAction(integrations, telegram);

    @Test
    void sendsFormattedSummaryToPairedChat() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.TELEGRAM)))
                .thenReturn("123456789");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH", null)));

        String result = action.execute(ctx, Map.of());

        assertThat(result).isEqualTo("Mensagem enviada no Telegram");
        assertThat(telegram.messages).hasSize(1);
        RecordingTelegram.Message msg = telegram.messages.get(0);
        assertThat(msg.chatId()).isEqualTo("123456789");
        assertThat(msg.html()).contains("<b>Renovação Acme</b>");
        assertThat(msg.html()).contains("1 decisão").contains("1 action items");
        assertThat(msg.html()).contains("Próximos passos").contains("• Enviar proposta — Ana");
        assertThat(msg.html()).contains(ctx.meetingUrl());
    }

    @Test
    void limitsToFiveNextStepsWithRemainderCount() {
        List<WorkflowEventContext.ActionItemView> items = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            items.add(new WorkflowEventContext.ActionItemView("Item " + i, null, "LOW", null));
        }
        String html =
                TelegramSendMessageAction.buildHtml(
                        TestContexts.context("Reunião", "Resumo.", items));

        assertThat(html).contains("• Item 5").doesNotContain("• Item 6");
        assertThat(html).contains("… e mais 2");
    }

    /** Dynamic content escaped — parse_mode HTML would break with a raw <. */
    @Test
    void escapesHtmlContent() {
        String html =
                TelegramSendMessageAction.buildHtml(
                        TestContexts.context(
                                "Specs <v2> & roadmap",
                                "Resumo.",
                                List.of(
                                        new WorkflowEventContext.ActionItemView(
                                                "Revisar <script>", "P&D", "LOW", null))));

        assertThat(html).contains("<b>Specs &lt;v2&gt; &amp; roadmap</b>");
        assertThat(html).contains("• Revisar &lt;script&gt; — P&amp;D");
    }

    /** Captures sends in memory (replaces the real HTTP calls to the Bot API). */
    static class RecordingTelegram extends TelegramBotHttpClient {
        record Message(String chatId, String html) {}

        final List<Message> messages = new ArrayList<>();

        RecordingTelegram() {
            super(new ObjectMapper(), "bot-token-teste");
        }

        @Override
        public String sendMessageHtml(String chatId, String html) {
            messages.add(new Message(chatId, html));
            return String.valueOf(messages.size());
        }
    }
}

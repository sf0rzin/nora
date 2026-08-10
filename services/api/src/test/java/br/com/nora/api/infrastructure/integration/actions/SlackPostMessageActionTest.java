package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Text rendering of the slack_post_message action (default, custom and truncation). */
class SlackPostMessageActionTest {

    @Test
    void defaultText_appliesPlaceholdersAndAppendsLink() {
        WorkflowEventContext ctx = context("Renovação Acme", "Resumo curto da reunião.");
        String text = SlackPostMessageAction.defaultText(ctx);
        assertThat(text).startsWith("*Renovação Acme* analisada pelo NORA — resumo: Resumo curto");
        assertThat(text).endsWith("\nhttp://localhost:3000/meetings/" + ctx.meetingId());
    }

    @Test
    void defaultText_truncatesLongSummaryBeforeLink() {
        WorkflowEventContext ctx = context("Reunião X", "a".repeat(2000));
        String text = SlackPostMessageAction.defaultText(ctx);
        String[] lines = text.split("\n", 2);
        // Body capped at ~400 chars (with ellipsis); the link comes whole on the next line.
        assertThat(lines[0]).hasSize(SlackPostMessageAction.DEFAULT_TEXT_MAX);
        assertThat(lines[0]).endsWith("…");
        assertThat(lines[1]).isEqualTo("http://localhost:3000/meetings/" + ctx.meetingId());
    }

    @Test
    void customText_usesUserTemplateWithPlaceholders() {
        WorkflowEventContext ctx = context("Kickoff Beta", "Resumo.");
        String text =
                SlackPostMessageAction.renderText(
                        Map.of("text", "Look at this: {{meeting.title}} ({{meeting.url}})"), ctx);
        assertThat(text)
                .isEqualTo(
                        "Look at this: Kickoff Beta (http://localhost:3000/meetings/"
                                + ctx.meetingId()
                                + ")");
    }

    @Test
    void customTextBlank_fallsBackToDefault() {
        WorkflowEventContext ctx = context("Kickoff Beta", "Resumo.");
        assertThat(SlackPostMessageAction.renderText(Map.of("text", "  "), ctx))
                .isEqualTo(SlackPostMessageAction.defaultText(ctx));
    }

    @Test
    void channelRequired_missingFailsClearly() {
        assertThatThrownBy(() -> SlackPostMessageAction.requiredChannel(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.channel");
        assertThat(SlackPostMessageAction.requiredChannel(Map.of("channel", " #vendas ")))
                .isEqualTo("#vendas");
    }

    private static WorkflowEventContext context(String title, String summary) {
        UUID meetingId = UUID.randomUUID();
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                meetingId,
                title,
                List.of("flows"),
                summary,
                summary,
                1,
                2,
                1,
                List.of(),
                70,
                65,
                "http://localhost:3000/meetings/" + meetingId,
                OffsetDateTime.now(ZoneOffset.UTC),
                false);
    }
}

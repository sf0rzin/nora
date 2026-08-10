package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.WorkflowEventContext.ActionItemView;
import br.com.nora.api.infrastructure.integration.WebhookHttpClient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** discord_post_message action: webhookUrl validation, embed and failure propagation. */
class DiscordPostMessageActionTest {

    private static final String WEBHOOK = "https://discord.com/api/webhooks/123/abc";

    // ── webhookUrl validation ──

    @Test
    void webhookUrl_acceptsDiscordAndDiscordapp() {
        assertThat(DiscordPostMessageAction.requiredWebhookUrl(Map.of("webhookUrl", WEBHOOK)))
                .isEqualTo(WEBHOOK);
        assertThat(
                        DiscordPostMessageAction.requiredWebhookUrl(
                                Map.of(
                                        "webhookUrl",
                                        " https://discordapp.com/api/webhooks/9/xyz ")))
                .isEqualTo("https://discordapp.com/api/webhooks/9/xyz");
    }

    @Test
    void webhookUrl_missingFailsClearly() {
        assertThatThrownBy(() -> DiscordPostMessageAction.requiredWebhookUrl(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.webhookUrl");
    }

    @Test
    void webhookUrl_outsideDiscordDomainRejected() {
        for (String url :
                List.of(
                        "https://exemplo.com/api/webhooks/123/abc",
                        "http://discord.com/api/webhooks/123/abc",
                        "https://discord.com.evil.io/api/webhooks/123/abc")) {
            assertThatThrownBy(
                            () ->
                                    DiscordPostMessageAction.requiredWebhookUrl(
                                            Map.of("webhookUrl", url)))
                    .as("should reject %s", url)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("https://discord.com/api/webhooks/");
        }
    }

    // ── Embed ──

    @Test
    @SuppressWarnings("unchecked")
    void embed_carriesTitleSummaryColorFooterUrlAndFields() {
        UUID meetingId = UUID.randomUUID();
        WorkflowEventContext ctx = context(meetingId, "Resumo curto.", 62, 58, items(2));

        Map<String, Object> payload = DiscordPostMessageAction.buildPayload(ctx);

        assertThat(payload).containsEntry("username", "NORA");
        List<Map<String, Object>> embeds = (List<Map<String, Object>>) payload.get("embeds");
        assertThat(embeds).hasSize(1);
        Map<String, Object> embed = embeds.get(0);
        assertThat(embed)
                .containsEntry("title", "Renovação Acme")
                .containsEntry("description", "Resumo curto.")
                .containsEntry("url", "http://localhost:3000/meetings/" + meetingId)
                .containsEntry("color", 5162200)
                .containsEntry("footer", Map.of("text", "NORA Flows"));

        List<Map<String, Object>> fields = (List<Map<String, Object>>) embed.get("fields");
        assertThat(fields)
                .extracting(f -> f.get("name"))
                .containsExactly(
                        "Decisões",
                        "Action items",
                        "Riscos",
                        "Productivity",
                        "Confidence",
                        "Próximos passos");
        assertThat(fields.get(3)).containsEntry("value", "62/100").containsEntry("inline", true);
        assertThat(fields.get(5))
                .containsEntry("value", "• Item 1 — Ana\n• Item 2")
                .containsEntry("inline", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void embed_withNoScoresOrItemsOmitsOptionalFields() {
        WorkflowEventContext ctx = context(UUID.randomUUID(), null, null, null, List.of());

        Map<String, Object> embed =
                ((List<Map<String, Object>>)
                                DiscordPostMessageAction.buildPayload(ctx).get("embeds"))
                        .get(0);

        assertThat(embed).doesNotContainKey("description");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) embed.get("fields");
        assertThat(fields)
                .extracting(f -> f.get("name"))
                .containsExactly("Decisões", "Action items", "Riscos");
    }

    @Test
    @SuppressWarnings("unchecked")
    void embed_truncatesLongSummaryAndCapsNextStepsAtFive() {
        WorkflowEventContext ctx = context(UUID.randomUUID(), "a".repeat(4000), 62, 58, items(8));

        Map<String, Object> embed =
                ((List<Map<String, Object>>)
                                DiscordPostMessageAction.buildPayload(ctx).get("embeds"))
                        .get(0);

        String description = (String) embed.get("description");
        assertThat(description).hasSize(DiscordPostMessageAction.DESCRIPTION_MAX);
        assertThat(description).endsWith("…");

        List<Map<String, Object>> fields = (List<Map<String, Object>>) embed.get("fields");
        String steps = (String) fields.get(fields.size() - 1).get("value");
        assertThat(steps.split("\n")).hasSize(DiscordPostMessageAction.MAX_ACTION_ITEMS);
    }

    // ── Execution (mocked HTTP) ──

    @Test
    void execute_successWithStatus204() {
        WebhookHttpClient http = mock(WebhookHttpClient.class);
        when(http.postJson(
                        eq("discord"), eq(WEBHOOK), eq(Map.of("User-Agent", "NORA-Flows")), any()))
                .thenReturn(204);

        String result =
                new DiscordPostMessageAction(http)
                        .execute(
                                context(UUID.randomUUID(), "Resumo.", 62, 58, items(1)),
                                Map.of("webhookUrl", WEBHOOK));

        assertThat(result).isEqualTo("Mensagem enviada no Discord (HTTP 204)");
    }

    @Test
    void execute_discordFailurePropagates() {
        WebhookHttpClient http = mock(WebhookHttpClient.class);
        when(http.postJson(anyString(), anyString(), anyMap(), any()))
                .thenThrow(new IntegrationException.ProviderError("discord", "respondeu HTTP 404"));

        assertThatThrownBy(
                        () ->
                                new DiscordPostMessageAction(http)
                                        .execute(
                                                context(
                                                        UUID.randomUUID(),
                                                        "Resumo.",
                                                        62,
                                                        58,
                                                        items(1)),
                                                Map.of("webhookUrl", WEBHOOK)))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("404");
    }

    // ── Fixtures ──

    private static List<ActionItemView> items(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(
                        i -> new ActionItemView("Item " + i, i == 1 ? "Ana" : null, "MEDIUM", null))
                .toList();
    }

    private static WorkflowEventContext context(
            UUID meetingId,
            String summary,
            Integer productivity,
            Integer confidence,
            List<ActionItemView> actionItems) {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                meetingId,
                "Renovação Acme",
                List.of("renovacao"),
                summary,
                summary,
                2,
                3,
                1,
                actionItems,
                productivity,
                confidence,
                "http://localhost:3000/meetings/" + meetingId,
                OffsetDateTime.now(ZoneOffset.UTC),
                false);
    }
}

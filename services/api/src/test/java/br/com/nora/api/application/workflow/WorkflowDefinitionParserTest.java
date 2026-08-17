package br.com.nora.api.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.workflow.WorkflowDefinition.NodeKind;
import br.com.nora.api.domain.workflow.TriggerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionParserTest {

    private final WorkflowDefinitionParser parser =
            new WorkflowDefinitionParser(new ObjectMapper());
    private final Set<String> actions =
            Set.of("send_email", "slack_post_message", "call_webhook", "discord_post_message");

    private static final String VALID =
            """
            {
              "nodes": [
                {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                 "position":{"x":40,"y":120}},
                {"id":"c1","kind":"condition","type":"productivity_score_below",
                 "params":{"value":70},"position":{"x":200,"y":120}},
                {"id":"a1","kind":"action","type":"send_email",
                 "params":{"to":"x@nora.dev"},"position":{"x":360,"y":120}}
              ],
              "edges": [
                {"id":"e1","source":"t1","target":"c1"},
                {"id":"e2","source":"c1","target":"a1"}
              ]
            }
            """;

    @Test
    void validParse_buildsNavigableGraph() {
        WorkflowDefinition def = parser.parse(VALID, actions);
        assertThat(def.nodes()).hasSize(3);
        assertThat(def.triggerNode().type()).isEqualTo("meeting.analysis_completed");
        assertThat(def.childrenOf("t1"))
                .extracting(WorkflowDefinition.Node::id)
                .containsExactly("c1");
        assertThat(def.childrenOf("c1"))
                .extracting(WorkflowDefinition.Node::id)
                .containsExactly("a1");
        assertThat(def.nodesById().get("a1").kind()).isEqualTo(NodeKind.ACTION);
        assertThat(def.nodesById().get("c1").params()).containsEntry("value", 70);
        assertThat(def.hasCycle()).isFalse();
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("not json", actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class);
    }

    @Test
    void rejectsMissingTrigger() {
        String json =
                """
                {"nodes":[{"id":"a1","kind":"action","type":"send_email",
                "params":{"to":"x@nora.dev"}}],"edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("exactly one trigger");
    }

    @Test
    void rejectsTwoTriggers() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"t2","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("exactly one trigger");
    }

    @Test
    void rejectsUnknownTrigger() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.exploded"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("unknown trigger");
    }

    /**
     * The triggers the canvas offers are exactly the ones something dispatches. Since US75 that is
     * all four, and this loop is where the parity is asserted: a value added to the enum without a
     * dispatcher fails here rather than reaching a user as a flow that sits ACTIVE and never runs.
     */
    @Test
    void acceptsEveryDispatchedTrigger() {
        for (TriggerType trigger : TriggerType.values()) {
            if (!trigger.hasDispatcher()) {
                continue;
            }
            WorkflowDefinition def = parser.parse(flowWith(trigger.wire()), actions);
            assertThat(def.triggerNode().type()).isEqualTo(trigger.wire());
        }
        assertThat(TriggerType.SCHEDULE_CRON.hasDispatcher()).isTrue();
    }

    /**
     * {@code schedule.cron} was refused on save for its whole life before US75, because nothing
     * fired it. It is accepted now — but only carrying a schedule the runner can actually honour.
     */
    @Test
    void acceptsScheduleCronWithAValidSchedule() {
        String weekly = "{\"frequency\":\"weekly\",\"weekday\":\"MON\",\"hour\":9,\"minute\":0}";
        WorkflowDefinition def = parser.parse(flowWith("schedule.cron", weekly), actions);
        assertThat(def.triggerNode().type()).isEqualTo("schedule.cron");
        assertThat(def.triggerNode().params()).containsEntry("weekday", "MON");
    }

    /**
     * The half that keeps the acceptance honest: what the scheduler cannot honour is refused at
     * save, not accepted and quietly drifted. A trigger with no schedule at all is the case a
     * definition written by hand hits first.
     */
    @Test
    void rejectsScheduleCronWithNoSchedule() {
        assertThatThrownBy(() -> parser.parse(flowWith("schedule.cron", "{}"), actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("On a schedule")
                .hasMessageContaining("frequency");
    }

    /** A full expression is refused by name — the vocabulary cannot honour "every second". */
    @Test
    void rejectsScheduleCronWithARawExpression() {
        String raw = "{\"cron\":\"* * * * * *\",\"frequency\":\"daily\",\"hour\":9,\"minute\":0}";
        assertThatThrownBy(() -> parser.parse(flowWith("schedule.cron", raw), actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("does not take a raw");
    }

    @Test
    void rejectsScheduleCronWithAnOutOfRangeHour() {
        String params = "{\"frequency\":\"daily\",\"hour\":99,\"minute\":0}";
        assertThatThrownBy(() -> parser.parse(flowWith("schedule.cron", params), actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("hour");
    }

    @Test
    void rejectsMissingAction() {
        String json =
                """
                {"nodes":[{"id":"t1","kind":"trigger","type":"meeting.analysis_completed"}],
                 "edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("at least one action");
    }

    @Test
    void rejectsUnknownAction() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"launch_rocket"}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("unknown action");
    }

    @Test
    void rejectsSendEmailWithoutRecipient() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"subject":"oi"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("recipient");
    }

    @Test
    void rejectsSlackPostMessageWithoutChannel() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"slack_post_message","params":{"text":"oi"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.channel");
    }

    @Test
    void acceptsSlackPostMessageWithChannel() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"slack_post_message",
                   "params":{"channel":"#vendas"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThat(parser.parse(json, actions).nodesById().get("a1").params())
                .containsEntry("channel", "#vendas");
    }

    @Test
    void rejectsGithubCreateIssueWithoutRepoOrWrongFormat() {
        Set<String> withGithub = Set.of("github_create_issue");
        String withoutRepo =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"github_create_issue","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(withoutRepo, withGithub))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.repo");

        String withoutSlash =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"github_create_issue",
                   "params":{"repo":"sem-barra"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(withoutSlash, withGithub))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("owner/name");
    }

    /** Wave 2: outlook_send_email requires a valid recipient, same as send_email/gmail. */
    @Test
    void rejectsOutlookSendEmailWithoutRecipient() {
        Set<String> withOutlook = Set.of("outlook_send_email");
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"outlook_send_email","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, withOutlook))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.to");
    }

    /** Wave 2: trello_create_card requires the board list. */
    @Test
    void rejectsTrelloCreateCardWithoutListId() {
        Set<String> withTrello = Set.of("trello_create_card");
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"trello_create_card","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, withTrello))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.listId");
    }

    @Test
    void rejectsCallWebhookWithoutHttpsUrl() {
        for (String params : new String[] {"{}", "{\"url\":\"http://exemplo.com/hook\"}"}) {
            String json =
                    """
                    {"nodes":[
                      {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                      {"id":"a1","kind":"action","type":"call_webhook","params":%s}],
                     "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                    """
                            .formatted(params);
            assertThatThrownBy(() -> parser.parse(json, actions))
                    .isInstanceOf(WorkflowException.InvalidDefinition.class)
                    .hasMessageContaining("https://");
        }
    }

    @Test
    void rejectsNotionCreatePageWithoutParentPage() {
        String withoutParent =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"notion_create_page","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(withoutParent, Set.of("notion_create_page")))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.parentPageId");
    }

    @Test
    void acceptsCallWebhookWithHttpsUrl() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"call_webhook",
                   "params":{"url":"https://hooks.exemplo.com/nora"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThat(parser.parse(json, actions).nodesById().get("a1").params())
                .containsEntry("url", "https://hooks.exemplo.com/nora");
    }

    @Test
    void rejectsDiscordWithoutDiscordWebhook() {
        for (String params :
                new String[] {"{}", "{\"webhookUrl\":\"https://exemplo.com/webhooks/1/a\"}"}) {
            String json =
                    """
                    {"nodes":[
                      {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                      {"id":"a1","kind":"action","type":"discord_post_message","params":%s}],
                     "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                    """
                            .formatted(params);
            assertThatThrownBy(() -> parser.parse(json, actions))
                    .isInstanceOf(WorkflowException.InvalidDefinition.class)
                    .hasMessageContaining("discord.com/api/webhooks");
        }
    }

    @Test
    void acceptsDiscordWithValidWebhook() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"discord_post_message",
                   "params":{"webhookUrl":"https://discord.com/api/webhooks/123/abc"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThat(parser.parse(json, actions).nodesById().get("a1").params())
                .containsEntry("webhookUrl", "https://discord.com/api/webhooks/123/abc");
    }

    @Test
    void rejectsEdgeToNonexistentNode() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"fantasma"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("nonexistent node");
    }

    @Test
    void rejectsCycle() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}},
                  {"id":"a2","kind":"action","type":"send_email","params":{"to":"y@nora.dev"}}],
                 "edges":[
                  {"id":"e1","source":"t1","target":"a1"},
                  {"id":"e2","source":"a1","target":"a2"},
                  {"id":"e3","source":"a2","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("cycles");
    }

    @Test
    void rejectsDuplicateId() {
        String json =
                """
                {"nodes":[
                  {"id":"n1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"n1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnknownCondition() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"c1","kind":"condition","type":"fase_da_lua"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"c1"},
                          {"id":"e2","source":"c1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("unknown condition");
    }

    /** Minimal valid flow (one trigger wired to one action) parameterized by the trigger wire. */
    private String flowWith(String triggerWire) {
        return flowWith(triggerWire, defaultTriggerParams(triggerWire));
    }

    /** Same, with the trigger's own params. {@code schedule.cron} is the only trigger with any. */
    private String flowWith(String triggerWire, String triggerParams) {
        return """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"%s","params":%s},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """
                .formatted(triggerWire, triggerParams);
    }

    private static String defaultTriggerParams(String triggerWire) {
        return "schedule.cron".equals(triggerWire)
                ? "{\"frequency\":\"daily\",\"hour\":9,\"minute\":0}"
                : "{}";
    }
}

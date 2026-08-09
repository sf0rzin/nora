package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.MicrosoftGraphClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * mscalendar_create_event action: follow-up in Outlook Calendar with the Google calendar defaults.
 */
class MicrosoftCalendarCreateEventActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingGraph graph = new RecordingGraph();
    private final MicrosoftCalendarCreateEventAction action =
            new MicrosoftCalendarCreateEventAction(integrations, graph);

    @Test
    void defaults_amanha10hSaoPaulo30min() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.MICROSOFT)))
                .thenReturn("ms_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH", null)));

        String result = action.execute(ctx, Map.of());

        assertThat(result).contains("Follow-up: Renovação Acme");
        assertThat(graph.events).hasSize(1);
        RecordingGraph.Event event = graph.events.get(0);
        assertThat(event.token()).isEqualTo("ms_token");
        assertThat(event.title()).isEqualTo("Follow-up: Renovação Acme");
        assertThat(event.timeZone()).isEqualTo("America/Sao_Paulo");
        assertThat(event.start().getHour()).isEqualTo(10);
        assertThat(event.start().getMinute()).isZero();
        assertThat(event.end()).isEqualTo(event.start().plusMinutes(30));
        assertThat(event.description()).contains("Resumo.").contains("Enviar proposta — Ana");
    }

    @Test
    void paramsCustom_diasHoraDuracao() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.MICROSOFT)))
                .thenReturn("ms_token");
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());

        action.execute(
                ctx,
                Map.of(
                        "title", "Retro: {{meeting.title}}",
                        "startInDays", 3,
                        "hour", 15,
                        "durationMinutes", 45));

        RecordingGraph.Event event = graph.events.get(0);
        assertThat(event.title()).isEqualTo("Retro: Kickoff");
        assertThat(event.start().getHour()).isEqualTo(15);
        assertThat(event.end()).isEqualTo(event.start().plusMinutes(45));
    }

    /** Graph gotcha (same as Google Calendar): dateTime WITHOUT seconds = 400. */
    @Test
    void graphLocal_sempreComSegundosESemOffset() {
        OffsetDateTime meiaHora = OffsetDateTime.parse("2026-06-13T10:00:00-03:00");
        assertThat(MicrosoftGraphClient.graphLocal(meiaHora)).isEqualTo("2026-06-13T10:00:00");
    }

    /** Captures event creations in memory (replaces the real HTTP calls to Graph). */
    static class RecordingGraph extends MicrosoftGraphClient {
        record Event(
                String token,
                String title,
                String description,
                OffsetDateTime start,
                OffsetDateTime end,
                String timeZone) {}

        final List<Event> events = new ArrayList<>();

        RecordingGraph() {
            super(new ObjectMapper());
        }

        @Override
        public String createEvent(
                String accessToken,
                String title,
                String description,
                OffsetDateTime start,
                OffsetDateTime end,
                String timeZone) {
            events.add(new Event(accessToken, title, description, start, end, timeZone));
            return "https://outlook.live.com/calendar/event-" + events.size();
        }
    }
}

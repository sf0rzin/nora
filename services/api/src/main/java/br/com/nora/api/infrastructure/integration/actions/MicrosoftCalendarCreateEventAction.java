package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.FollowUpSchedule;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.MicrosoftGraphClient;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Criar evento no Outlook Calendar" action — REAL follow-up in the connected Microsoft
 * account's primary calendar (Graph), mirror of Google's {@code calendar_create_event}. Params (all
 * optional): {@code title} (placeholders supported; default "Follow-up: {{meeting.title}}"), {@code
 * startInDays} (default 1, tomorrow), {@code hour} (default 10, São Paulo time), {@code
 * durationMinutes} (default 30). The description carries the meeting summary + next steps.
 */
@Component
public class MicrosoftCalendarCreateEventAction implements ActionExecutor {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final IntegrationService integrations;
    private final MicrosoftGraphClient graph;

    public MicrosoftCalendarCreateEventAction(
            IntegrationService integrations, MicrosoftGraphClient graph) {
        this.integrations = integrations;
        this.graph = graph;
    }

    @Override
    public String type() {
        return "mscalendar_create_event";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String titleTemplate = WorkflowActionTemplates.stringParam(params, "title");
        if (titleTemplate == null || titleTemplate.isBlank()) {
            titleTemplate = "Follow-up: {{meeting.title}}";
        }
        String title = WorkflowActionTemplates.applyPlaceholders(titleTemplate, ctx, false);

        FollowUpSchedule.Resolved agenda =
                FollowUpSchedule.resolve(ctx, params, OffsetDateTime.now(SAO_PAULO));

        String accessToken =
                integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.MICROSOFT);
        String link =
                graph.createEvent(
                        accessToken,
                        title,
                        description(ctx),
                        agenda.start(),
                        agenda.end(),
                        SAO_PAULO.getId());
        return "Evento criado no Outlook Calendar: \""
                + title
                + "\""
                + (agenda.origem() == null ? "" : " — agendado pelo " + agenda.origem())
                + " — "
                + link;
    }

    private String description(WorkflowEventContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.summary() != null) {
            sb.append(ctx.summary()).append("\n\n");
        }
        if (!ctx.actionItems().isEmpty()) {
            sb.append("Próximos passos:\n");
            for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
                sb.append("• ")
                        .append(item.title())
                        .append(
                                item.assignee() == null || item.assignee().isBlank()
                                        ? ""
                                        : " — " + item.assignee())
                        .append("\n");
            }
        }
        if (ctx.meetingUrl() != null) {
            sb.append("\nAbrir no NORA: ").append(ctx.meetingUrl());
        }
        return sb.toString().strip();
    }
}

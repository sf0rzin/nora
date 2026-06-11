package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.infrastructure.integration.GoogleWorkspaceClient;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Criar evento no Google Calendar" do NORA Flows — cria um follow-up REAL no calendário
 * primário da conta Google conectada. Params (todos opcionais): {@code title} (placeholders
 * suportados; default "Follow-up: {{meeting.title}}"), {@code startInDays} (default 1, amanhã),
 * {@code hour} (default 10, horário de São Paulo), {@code durationMinutes} (default 30). A
 * descrição leva o resumo + próximos passos da reunião.
 */
@Component
public class CalendarCreateEventAction implements ActionExecutor {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    private final IntegrationService integrations;
    private final GoogleWorkspaceClient google;

    public CalendarCreateEventAction(
            IntegrationService integrations, GoogleWorkspaceClient google) {
        this.integrations = integrations;
        this.google = google;
    }

    @Override
    public String type() {
        return "calendar_create_event";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String titleTemplate = WorkflowActionTemplates.stringParam(params, "title");
        if (titleTemplate == null || titleTemplate.isBlank()) {
            titleTemplate = "Follow-up: {{meeting.title}}";
        }
        String title = WorkflowActionTemplates.applyPlaceholders(titleTemplate, ctx, false);

        int startInDays = intParam(params, "startInDays", 1);
        int hour = intParam(params, "hour", 10);
        int durationMinutes = intParam(params, "durationMinutes", 30);

        OffsetDateTime start =
                OffsetDateTime.now(SAO_PAULO)
                        .plusDays(startInDays)
                        .withHour(Math.min(Math.max(hour, 0), 23))
                        .withMinute(0)
                        .withSecond(0)
                        .withNano(0);
        OffsetDateTime end = start.plusMinutes(Math.max(durationMinutes, 5));

        String accessToken = integrations.validGoogleAccessToken(ctx.tenantId());
        String link = google.createCalendarEvent(accessToken, title, description(ctx), start, end);
        return "Evento criado no Google Calendar: \"" + title + "\" — " + link;
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

    private static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object raw = params.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // cai no default
            }
        }
        return defaultValue;
    }
}

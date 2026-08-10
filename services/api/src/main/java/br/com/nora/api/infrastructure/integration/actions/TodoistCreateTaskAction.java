package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TodoistClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Create tasks in Todoist" action — REAL tasks in the connected account's Inbox, one
 * per meeting action item (content = item title; description = meeting + assignee + link). No
 * required params. The Flows context does not expose dueDate — the task is born with no due date.
 *
 * <p>A meeting with no action items is NOT a failure: the action honestly records that nothing was
 * created. A provider failure PROPAGATES ({@link ActionExecutor} contract) — the engine writes
 * FAILED in the log.
 */
@Component
public class TodoistCreateTaskAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final TodoistClient todoist;

    public TodoistCreateTaskAction(IntegrationService integrations, TodoistClient todoist) {
        this.integrations = integrations;
        this.todoist = todoist;
    }

    @Override
    public String type() {
        return "todoist_create_task";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        if (ctx.actionItems().isEmpty()) {
            return "Reunião sem action items — nenhuma tarefa criada no Todoist";
        }
        String token = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.TODOIST);
        for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
            todoist.createTask(token, item.title(), description(item, ctx));
        }
        int count = ctx.actionItems().size();
        return count + (count == 1 ? " tarefa criada" : " tarefas criadas") + " no Todoist";
    }

    /** Task description: meeting + assignee + link in NORA. */
    static String description(WorkflowEventContext.ActionItemView item, WorkflowEventContext ctx) {
        StringBuilder description =
                new StringBuilder("Reunião: ")
                        .append(ctx.meetingTitle() == null ? "" : ctx.meetingTitle());
        if (item.assignee() != null && !item.assignee().isBlank()) {
            description.append(" — Responsável: ").append(item.assignee());
        }
        if (ctx.meetingUrl() != null) {
            description.append("\n").append(ctx.meetingUrl());
        }
        return description.toString();
    }
}

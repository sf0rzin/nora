package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TrelloHttpClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * NORA Flows "Create cards in Trello" action — REAL cards in the given list, with the token the
 * user pasted on connect. Params: {@code listId} (required, id of the board's list). One card per
 * meeting action item (name = title; desc = meeting + assignee + link; due = the item's due date
 * when the analysis extracted one).
 *
 * <p>A meeting with no action items is NOT a failure: the action honestly records that nothing was
 * created. A provider failure PROPAGATES ({@link ActionExecutor} contract) — the engine writes
 * FAILED in the log.
 */
@Component
public class TrelloCreateCardAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final TrelloHttpClient trello;

    public TrelloCreateCardAction(IntegrationService integrations, TrelloHttpClient trello) {
        this.integrations = integrations;
        this.trello = trello;
    }

    @Override
    public String type() {
        return "trello_create_card";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String listId = requiredListId(params);
        if (ctx.actionItems().isEmpty()) {
            return "Reunião sem action items — nenhum card criado no Trello";
        }
        String token = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.TRELLO);
        for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
            trello.createCard(token, listId, item.title(), description(item, ctx), item.dueDate());
        }
        int count = ctx.actionItems().size();
        return count + (count == 1 ? " card criado" : " cards criados") + " no Trello";
    }

    /** Card desc: meeting + assignee + link in NORA. */
    static String description(WorkflowEventContext.ActionItemView item, WorkflowEventContext ctx) {
        StringBuilder desc =
                new StringBuilder("Reunião: ")
                        .append(ctx.meetingTitle() == null ? "" : ctx.meetingTitle());
        if (item.assignee() != null && !item.assignee().isBlank()) {
            desc.append(" — Responsável: ").append(item.assignee());
        }
        if (ctx.meetingUrl() != null) {
            desc.append("\n").append(ctx.meetingUrl());
        }
        return desc.toString();
    }

    static String requiredListId(Map<String, Object> params) {
        String listId = WorkflowActionTemplates.stringParam(params, "listId");
        if (listId == null || listId.isBlank()) {
            throw new IllegalArgumentException(
                    "list required in params.listId (id of the board's list in Trello)");
        }
        return listId.trim();
    }
}

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
 * Ação "Criar cards no Trello" do NORA Flows — cards REAIS na lista informada, com o token colado
 * pelo usuário na conexão. Params: {@code listId} (obrigatório, id da lista do board). Um card por
 * action item da reunião (name = título; desc = reunião + responsável + link; due = prazo do item
 * quando a análise extraiu).
 *
 * <p>Reunião sem action items NÃO é falha: a ação registra honestamente que nada foi criado. Falha
 * de provedor PROPAGA (contrato do {@link ActionExecutor}) — o engine grava FAILED no log.
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

    /** Desc do card: reunião + responsável + link no NORA. */
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
                    "lista obrigatória em params.listId (id da lista do board no Trello)");
        }
        return listId.trim();
    }
}

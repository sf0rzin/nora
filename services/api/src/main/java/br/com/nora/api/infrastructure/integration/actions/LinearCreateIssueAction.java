package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.LinearClient;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Criar issues no Linear" do NORA Flows — issues REAIS via GraphQL com a conta conectada, uma
 * por action item da reunião (title = título do item; description = reunião + responsável + link).
 * Param {@code teamKey} opcional (ex.: {@code ENG}); sem ele, usa o primeiro team do workspace.
 *
 * <p>Reunião sem action items NÃO é falha: a ação registra honestamente que nada foi criado. Falha
 * de provedor PROPAGA (contrato do {@link ActionExecutor}) — o engine grava FAILED no log.
 */
@Component
public class LinearCreateIssueAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final LinearClient linear;

    public LinearCreateIssueAction(IntegrationService integrations, LinearClient linear) {
        this.integrations = integrations;
        this.linear = linear;
    }

    @Override
    public String type() {
        return "linear_create_issue";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        if (ctx.actionItems().isEmpty()) {
            return "Reunião sem action items — nenhuma issue criada no Linear";
        }
        String token = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.LINEAR);
        String teamKey = WorkflowActionTemplates.stringParam(params, "teamKey");
        String teamId =
                teamKey == null || teamKey.isBlank()
                        ? linear.firstTeamId(token)
                        : linear.teamIdByKey(token, teamKey.trim());
        for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
            linear.createIssue(token, teamId, item.title(), description(item, ctx));
        }
        int count = ctx.actionItems().size();
        return count + (count == 1 ? " issue criada" : " issues criadas") + " no Linear";
    }

    /** Description da issue: reunião + responsável + link no NORA (markdown do Linear). */
    static String description(WorkflowEventContext.ActionItemView item, WorkflowEventContext ctx) {
        StringBuilder description =
                new StringBuilder("**Reunião:** ")
                        .append(ctx.meetingTitle() == null ? "" : ctx.meetingTitle());
        if (item.assignee() != null && !item.assignee().isBlank()) {
            description.append("\n**Responsável:** ").append(item.assignee());
        }
        if (ctx.meetingUrl() != null) {
            description.append("\n\n[Abrir no NORA](").append(ctx.meetingUrl()).append(")");
        }
        return description.toString();
    }
}

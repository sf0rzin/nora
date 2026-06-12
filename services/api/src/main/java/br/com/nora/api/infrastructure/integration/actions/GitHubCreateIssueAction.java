package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.GitHubClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Criar issue no GitHub" do NORA Flows — issues REAIS no repositório informado, com a conta
 * GitHub conectada (OAuth). Params: {@code repo} (obrigatório, formato {@code owner/nome}) e {@code
 * title} opcional com placeholders ({{meeting.title}} etc.).
 *
 * <p>Comportamento default (sem {@code title}): UMA issue por action item da reunião (título do
 * item; corpo com reunião/responsável/prioridade — o contexto do Flows não expõe sourceQuote nem
 * dueDate). Sem action items, uma issue única com o resumo. Com {@code title} custom: uma issue
 * única com esse título. Todas com a label {@code nora}.
 *
 * <p>Falha PROPAGA (contrato do {@link ActionExecutor}) — o engine grava FAILED no log.
 */
@Component
public class GitHubCreateIssueAction implements ActionExecutor {

    private static final List<String> LABELS = List.of("nora");

    private final IntegrationService integrations;
    private final GitHubClient github;

    public GitHubCreateIssueAction(IntegrationService integrations, GitHubClient github) {
        this.integrations = integrations;
        this.github = github;
    }

    @Override
    public String type() {
        return "github_create_issue";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String repo = requiredRepo(params);
        String token = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.GITHUB);

        String customTitle = WorkflowActionTemplates.stringParam(params, "title");
        if (customTitle != null && !customTitle.isBlank()) {
            String title = WorkflowActionTemplates.applyPlaceholders(customTitle, ctx, false);
            github.createIssue(token, repo, title, summaryBody(ctx), LABELS);
            return "1 issue criada no GitHub em " + repo;
        }

        if (ctx.actionItems().isEmpty()) {
            github.createIssue(
                    token,
                    repo,
                    "NORA — Reunião analisada: " + ctx.meetingTitle(),
                    summaryBody(ctx),
                    LABELS);
            return "1 issue criada no GitHub em " + repo + " (reunião sem action items)";
        }

        for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
            github.createIssue(token, repo, item.title(), itemBody(item, ctx), LABELS);
        }
        int count = ctx.actionItems().size();
        return count + (count == 1 ? " issue criada" : " issues criadas") + " no GitHub em " + repo;
    }

    /** Corpo da issue de um action item: reunião, responsável e prioridade + link no NORA. */
    static String itemBody(WorkflowEventContext.ActionItemView item, WorkflowEventContext ctx) {
        StringBuilder body =
                new StringBuilder("**Reunião:** ").append(nullSafe(ctx.meetingTitle()));
        if (item.assignee() != null && !item.assignee().isBlank()) {
            body.append("\n**Responsável:** ").append(item.assignee());
        }
        if (item.priority() != null && !item.priority().isBlank()) {
            body.append("\n**Prioridade:** ").append(item.priority());
        }
        appendLink(body, ctx);
        return body.toString();
    }

    /** Corpo da issue única (title custom ou reunião sem action items): resumo + link. */
    static String summaryBody(WorkflowEventContext ctx) {
        StringBuilder body =
                new StringBuilder("**Reunião:** ").append(nullSafe(ctx.meetingTitle()));
        if (ctx.summary() != null && !ctx.summary().isBlank()) {
            body.append("\n\n").append(ctx.summary());
        }
        appendLink(body, ctx);
        return body.toString();
    }

    static String requiredRepo(Map<String, Object> params) {
        String repo = WorkflowActionTemplates.stringParam(params, "repo");
        if (repo == null || repo.isBlank() || !repo.trim().contains("/")) {
            throw new IllegalArgumentException(
                    "repositório obrigatório em params.repo no formato owner/nome"
                            + " (ex.: stratfy/nora)");
        }
        return repo.trim();
    }

    private static void appendLink(StringBuilder body, WorkflowEventContext ctx) {
        if (ctx.meetingUrl() != null) {
            body.append("\n\n[Abrir no NORA](").append(ctx.meetingUrl()).append(")");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

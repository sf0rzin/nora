package br.com.nora.api.infrastructure.integration.actions;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.ActionExecutor;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.application.workflow.actions.WorkflowActionTemplates;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.NotionClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Ação "Criar página no Notion" do NORA Flows — página REAL filha de {@code parentPageId}
 * (obrigatório; a página pai precisa estar compartilhada com a integração no Notion). Conteúdo:
 * título da reunião + heading "Resumo" + parágrafo do resumo + bulleted list dos action items.
 *
 * <p>Falha PROPAGA (contrato do {@link ActionExecutor}) — o engine grava FAILED no log.
 */
@Component
public class NotionCreatePageAction implements ActionExecutor {

    private final IntegrationService integrations;
    private final NotionClient notion;

    public NotionCreatePageAction(IntegrationService integrations, NotionClient notion) {
        this.integrations = integrations;
        this.notion = notion;
    }

    @Override
    public String type() {
        return "notion_create_page";
    }

    @Override
    public String execute(WorkflowEventContext ctx, Map<String, Object> params) {
        String parentPageId = requiredParentPageId(params);
        String token = integrations.validAccessToken(ctx.tenantId(), IntegrationProvider.NOTION);
        notion.createPage(token, parentPageId, ctx.meetingTitle(), blocks(ctx));
        return "Página criada no Notion para a reunião \"" + ctx.meetingTitle() + "\"";
    }

    /** Blocos da página: heading + parágrafo do resumo + bulleted list dos action items. */
    static List<Object> blocks(WorkflowEventContext ctx) {
        List<Object> blocks = new ArrayList<>();
        blocks.add(heading("Resumo"));
        blocks.add(paragraph(ctx.summary() == null ? "(reunião sem resumo)" : ctx.summary()));
        if (!ctx.actionItems().isEmpty()) {
            blocks.add(heading("Action items"));
            for (WorkflowEventContext.ActionItemView item : ctx.actionItems()) {
                String text =
                        item.assignee() == null || item.assignee().isBlank()
                                ? item.title()
                                : item.title() + " — " + item.assignee();
                blocks.add(bulleted(text));
            }
        }
        if (ctx.meetingUrl() != null) {
            blocks.add(paragraph("Abrir no NORA: " + ctx.meetingUrl()));
        }
        return blocks;
    }

    static String requiredParentPageId(Map<String, Object> params) {
        String parentPageId = WorkflowActionTemplates.stringParam(params, "parentPageId");
        if (parentPageId == null || parentPageId.isBlank()) {
            throw new IllegalArgumentException(
                    "página pai obrigatória em params.parentPageId (id de uma página do Notion"
                            + " compartilhada com a integração)");
        }
        return parentPageId.trim();
    }

    private static Map<String, Object> heading(String text) {
        return Map.of(
                "object",
                "block",
                "type",
                "heading_2",
                "heading_2",
                Map.of("rich_text", rich(text)));
    }

    private static Map<String, Object> paragraph(String text) {
        return Map.of(
                "object",
                "block",
                "type",
                "paragraph",
                "paragraph",
                Map.of("rich_text", rich(text)));
    }

    private static Map<String, Object> bulleted(String text) {
        return Map.of(
                "object",
                "block",
                "type",
                "bulleted_list_item",
                "bulleted_list_item",
                Map.of("rich_text", rich(text)));
    }

    private static List<Object> rich(String text) {
        return List.of(Map.of("type", "text", "text", Map.of("content", text)));
    }
}

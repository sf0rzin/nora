package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.NotionClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ação notion_create_page: página filha com heading + resumo + bulleted list dos action items. */
class NotionCreatePageActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingNotion notion = new RecordingNotion();
    private final NotionCreatePageAction action = new NotionCreatePageAction(integrations, notion);

    @Test
    void criaPaginaFilhaComTituloDaReuniaoEBlocos() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.NOTION)))
                .thenReturn("ntn_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo da reunião.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH", null)));

        String result = action.execute(ctx, Map.of("parentPageId", "page-123"));

        assertThat(result).contains("Página criada no Notion").contains("Renovação Acme");
        assertThat(notion.pages).hasSize(1);
        RecordingNotion.Page page = notion.pages.get(0);
        assertThat(page.token()).isEqualTo("ntn_token");
        assertThat(page.parentPageId()).isEqualTo("page-123");
        assertThat(page.title()).isEqualTo("Renovação Acme");
        // heading Resumo + parágrafo + heading Action items + 1 bullet + link.
        assertThat(page.children()).hasSize(5);
        assertThat(page.children().toString()).contains("Resumo da reunião.");
        assertThat(page.children().toString()).contains("Enviar proposta — Ana");
    }

    @Test
    void semActionItems_paginaSoComResumo() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.NOTION)))
                .thenReturn("ntn_token");
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());

        action.execute(ctx, Map.of("parentPageId", "page-123"));

        // heading Resumo + parágrafo + link (sem bloco de action items).
        assertThat(notion.pages.get(0).children()).hasSize(3);
    }

    @Test
    void parentPageIdObrigatorio() {
        assertThatThrownBy(() -> NotionCreatePageAction.requiredParentPageId(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.parentPageId");
        assertThat(NotionCreatePageAction.requiredParentPageId(Map.of("parentPageId", " abc ")))
                .isEqualTo("abc");
    }

    /** Captura criações de página em memória (substitui as chamadas HTTP reais ao Notion). */
    static class RecordingNotion extends NotionClient {
        record Page(String token, String parentPageId, String title, List<Object> children) {}

        final List<Page> pages = new ArrayList<>();

        RecordingNotion() {
            super(new ObjectMapper());
        }

        @Override
        public String createPage(
                String accessToken, String parentPageId, String title, List<Object> children) {
            pages.add(new Page(accessToken, parentPageId, title, children));
            return "https://notion.so/page-" + pages.size();
        }
    }
}

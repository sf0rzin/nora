package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TrelloHttpClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** trello_create_card action: one card per action item in the given list; due when extracted. */
class TrelloCreateCardActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingTrello trello = new RecordingTrello();
    private final TrelloCreateCardAction action = new TrelloCreateCardAction(integrations, trello);

    @Test
    void criaUmCardPorActionItemComDue() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.TRELLO)))
                .thenReturn("trello_token");
        LocalDate prazo = LocalDate.of(2026, 6, 20);
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH", prazo),
                                new WorkflowEventContext.ActionItemView(
                                        "Agendar follow-up", null, "LOW", null)));

        String result = action.execute(ctx, Map.of("listId", "lista-1"));

        assertThat(result).isEqualTo("2 cards criados no Trello");
        assertThat(trello.cards).hasSize(2);
        RecordingTrello.Card first = trello.cards.get(0);
        assertThat(first.token()).isEqualTo("trello_token");
        assertThat(first.listId()).isEqualTo("lista-1");
        assertThat(first.name()).isEqualTo("Enviar proposta");
        assertThat(first.desc())
                .contains("Reunião: Renovação Acme")
                .contains("Responsável: Ana")
                .contains(ctx.meetingUrl());
        assertThat(first.due()).isEqualTo(prazo);
        // Item with no due date/assignee: card with no due and no assignee line.
        assertThat(trello.cards.get(1).due()).isNull();
        assertThat(trello.cards.get(1).desc()).doesNotContain("Responsável");
    }

    @Test
    void semActionItems_naoChamaProvedorERegistraHonesto() {
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());

        String result = action.execute(ctx, Map.of("listId", "lista-1"));

        assertThat(result).contains("nenhum card criado");
        assertThat(trello.cards).isEmpty();
        verifyNoInteractions(integrations);
    }

    @Test
    void listIdObrigatorio() {
        assertThatThrownBy(() -> TrelloCreateCardAction.requiredListId(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.listId");
        assertThat(TrelloCreateCardAction.requiredListId(Map.of("listId", " lista-1 ")))
                .isEqualTo("lista-1");
    }

    /** Ensures provider failure PROPAGATES (engine records FAILED). */
    @Test
    void falhaDoProvedorPropaga() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.TRELLO)))
                .thenReturn("trello_token");
        TrelloCreateCardAction failing =
                new TrelloCreateCardAction(
                        integrations,
                        new TrelloHttpClient(new ObjectMapper(), "key") {
                            @Override
                            public String createCard(
                                    String token,
                                    String listId,
                                    String name,
                                    String desc,
                                    LocalDate due) {
                                throw new IllegalStateException("falha simulada");
                            }
                        });
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Reunião",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Item", null, "LOW", null)));

        assertThatThrownBy(() -> failing.execute(ctx, Map.of("listId", "lista-1")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Captures card creations in memory (replaces the real HTTP calls to Trello). */
    static class RecordingTrello extends TrelloHttpClient {
        record Card(String token, String listId, String name, String desc, LocalDate due) {}

        final List<Card> cards = new ArrayList<>();

        RecordingTrello() {
            super(new ObjectMapper(), "key-teste");
        }

        @Override
        public String createCard(
                String token, String listId, String name, String desc, LocalDate due) {
            cards.add(new Card(token, listId, name, desc, due));
            return "https://trello.com/c/card-" + cards.size();
        }
    }
}

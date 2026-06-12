package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.TodoistClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ação todoist_create_task: uma tarefa por action item; reunião sem itens é no-op honesto. */
class TodoistCreateTaskActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingTodoist todoist = new RecordingTodoist();
    private final TodoistCreateTaskAction action =
            new TodoistCreateTaskAction(integrations, todoist);

    @Test
    void criaUmaTarefaPorActionItem() {
        when(integrations.validAccessToken(
                        eq(TestContexts.TENANT), eq(IntegrationProvider.TODOIST)))
                .thenReturn("td_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH"),
                                new WorkflowEventContext.ActionItemView(
                                        "Agendar follow-up", null, "LOW")));

        String result = action.execute(ctx, Map.of());

        assertThat(result).isEqualTo("2 tarefas criadas no Todoist");
        assertThat(todoist.tasks).hasSize(2);
        RecordingTodoist.Task first = todoist.tasks.get(0);
        assertThat(first.token()).isEqualTo("td_token");
        assertThat(first.content()).isEqualTo("Enviar proposta");
        assertThat(first.description())
                .contains("Reunião: Renovação Acme")
                .contains("Responsável: Ana")
                .contains(ctx.meetingUrl());
        assertThat(todoist.tasks.get(1).description()).doesNotContain("Responsável");
    }

    @Test
    void semActionItems_naoChamaProvedorERegistraHonesto() {
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());

        String result = action.execute(ctx, Map.of());

        assertThat(result).contains("nenhuma tarefa criada");
        assertThat(todoist.tasks).isEmpty();
        verifyNoInteractions(integrations);
    }

    /** Garante que falha do provedor PROPAGA (engine grava FAILED). */
    @Test
    void falhaDoProvedorPropaga() {
        when(integrations.validAccessToken(any(), any())).thenReturn("td_token");
        TodoistCreateTaskAction failing =
                new TodoistCreateTaskAction(
                        integrations,
                        new TodoistClient(new ObjectMapper()) {
                            @Override
                            public String createTask(
                                    String accessToken, String content, String description) {
                                throw new IllegalStateException("falha simulada");
                            }
                        });
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Reunião",
                        "Resumo.",
                        List.of(new WorkflowEventContext.ActionItemView("Item", null, "LOW")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> failing.execute(ctx, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Captura criações de tarefa em memória (substitui as chamadas HTTP reais ao Todoist). */
    static class RecordingTodoist extends TodoistClient {
        record Task(String token, String content, String description) {}

        final List<Task> tasks = new ArrayList<>();

        RecordingTodoist() {
            super(new ObjectMapper());
        }

        @Override
        public String createTask(String accessToken, String content, String description) {
            tasks.add(new Task(accessToken, content, description));
            return "https://todoist.com/task-" + tasks.size();
        }
    }
}

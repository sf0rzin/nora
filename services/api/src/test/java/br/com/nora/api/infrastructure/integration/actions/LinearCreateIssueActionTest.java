package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.LinearClient;
import br.com.nora.api.infrastructure.integration.actions.GitHubCreateIssueActionTest.TestContexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ação linear_create_issue: team default vs teamKey e uma issue por action item. */
class LinearCreateIssueActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingLinear linear = new RecordingLinear();
    private final LinearCreateIssueAction action =
            new LinearCreateIssueAction(integrations, linear);

    @Test
    void semTeamKey_usaPrimeiroTeamDoWorkspace() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.LINEAR)))
                .thenReturn("lin_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH")));

        String result = action.execute(ctx, Map.of());

        assertThat(result).isEqualTo("1 issue criada no Linear");
        assertThat(linear.firstTeamCalls).isEqualTo(1);
        assertThat(linear.issues).hasSize(1);
        RecordingLinear.Issue issue = linear.issues.get(0);
        assertThat(issue.teamId()).isEqualTo("team-default");
        assertThat(issue.title()).isEqualTo("Enviar proposta");
        assertThat(issue.description())
                .contains("Renovação Acme")
                .contains("Ana")
                .contains(ctx.meetingUrl());
    }

    @Test
    void comTeamKey_resolvePelaKey() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.LINEAR)))
                .thenReturn("lin_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Reunião",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView("Item A", null, "LOW"),
                                new WorkflowEventContext.ActionItemView("Item B", null, "LOW")));

        String result = action.execute(ctx, Map.of("teamKey", "ENG"));

        assertThat(result).isEqualTo("2 issues criadas no Linear");
        assertThat(linear.firstTeamCalls).isZero();
        assertThat(linear.resolvedKeys).containsExactly("ENG");
        assertThat(linear.issues).allMatch(i -> i.teamId().equals("team-ENG"));
    }

    @Test
    void semActionItems_naoChamaProvedorERegistraHonesto() {
        WorkflowEventContext ctx = TestContexts.context("Kickoff", "Resumo.", List.of());
        String result = action.execute(ctx, Map.of());
        assertThat(result).contains("nenhuma issue criada");
        assertThat(linear.issues).isEmpty();
    }

    /** Captura chamadas GraphQL em memória (substitui as chamadas HTTP reais ao Linear). */
    static class RecordingLinear extends LinearClient {
        record Issue(String token, String teamId, String title, String description) {}

        final List<Issue> issues = new ArrayList<>();
        final List<String> resolvedKeys = new ArrayList<>();
        int firstTeamCalls;

        RecordingLinear() {
            super(new ObjectMapper());
        }

        @Override
        public String firstTeamId(String accessToken) {
            firstTeamCalls++;
            return "team-default";
        }

        @Override
        public String teamIdByKey(String accessToken, String teamKey) {
            resolvedKeys.add(teamKey);
            return "team-" + teamKey;
        }

        @Override
        public String createIssue(
                String accessToken, String teamId, String title, String description) {
            issues.add(new Issue(accessToken, teamId, title, description));
            return "https://linear.app/issue-" + issues.size();
        }
    }
}

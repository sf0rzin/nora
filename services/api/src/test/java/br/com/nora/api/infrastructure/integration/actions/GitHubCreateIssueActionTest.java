package br.com.nora.api.infrastructure.integration.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.integration.IntegrationService;
import br.com.nora.api.application.workflow.WorkflowEventContext;
import br.com.nora.api.domain.integration.IntegrationProvider;
import br.com.nora.api.infrastructure.integration.GitHubClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** github_create_issue action: one issue per action item, summary fallback and custom title. */
class GitHubCreateIssueActionTest {

    private final IntegrationService integrations = mock(IntegrationService.class);
    private final RecordingGitHub github = new RecordingGitHub();
    private final GitHubCreateIssueAction action =
            new GitHubCreateIssueAction(integrations, github);

    @Test
    void default_criaUmaIssuePorActionItemComLabelNora() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.GITHUB)))
                .thenReturn("gho_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Renovação Acme",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Enviar proposta", "Ana", "HIGH", null),
                                new WorkflowEventContext.ActionItemView(
                                        "Agendar follow-up", null, "MEDIUM", null)));

        String result = action.execute(ctx, Map.of("repo", "stratfy/nora"));

        assertThat(result).isEqualTo("2 issues criadas no GitHub em stratfy/nora");
        assertThat(github.issues).hasSize(2);
        RecordingGitHub.Issue first = github.issues.get(0);
        assertThat(first.token()).isEqualTo("gho_token");
        assertThat(first.repo()).isEqualTo("stratfy/nora");
        assertThat(first.title()).isEqualTo("Enviar proposta");
        assertThat(first.body()).contains("Renovação Acme").contains("Ana").contains("HIGH");
        assertThat(first.body()).contains(ctx.meetingUrl());
        assertThat(first.labels()).containsExactly("nora");
        // An item with no assignee doesn't get the assignee line.
        assertThat(github.issues.get(1).body()).doesNotContain("Responsável");
    }

    @Test
    void semActionItems_criaIssueUnicaComResumo() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.GITHUB)))
                .thenReturn("gho_token");
        WorkflowEventContext ctx = TestContexts.context("Kickoff Beta", "Resumo geral.", List.of());

        String result = action.execute(ctx, Map.of("repo", "stratfy/nora"));

        assertThat(result).contains("1 issue criada").contains("sem action items");
        assertThat(github.issues).hasSize(1);
        assertThat(github.issues.get(0).title())
                .isEqualTo("NORA — Reunião analisada: Kickoff Beta");
        assertThat(github.issues.get(0).body()).contains("Resumo geral.");
    }

    @Test
    void tituloCustom_criaIssueUnicaComPlaceholders() {
        when(integrations.validAccessToken(eq(TestContexts.TENANT), eq(IntegrationProvider.GITHUB)))
                .thenReturn("gho_token");
        WorkflowEventContext ctx =
                TestContexts.context(
                        "Kickoff Beta",
                        "Resumo.",
                        List.of(
                                new WorkflowEventContext.ActionItemView(
                                        "Item", "Ana", "LOW", null)));

        String result =
                action.execute(
                        ctx, Map.of("repo", "stratfy/nora", "title", "Pós: {{meeting.title}}"));

        assertThat(result).isEqualTo("1 issue criada no GitHub em stratfy/nora");
        assertThat(github.issues).hasSize(1);
        assertThat(github.issues.get(0).title()).isEqualTo("Pós: Kickoff Beta");
    }

    @Test
    void repoObrigatorioNoFormatoOwnerNome() {
        assertThatThrownBy(() -> GitHubCreateIssueAction.requiredRepo(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("params.repo");
        assertThatThrownBy(() -> GitHubCreateIssueAction.requiredRepo(Map.of("repo", "sem-barra")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner/nome");
        assertThat(GitHubCreateIssueAction.requiredRepo(Map.of("repo", " stratfy/nora ")))
                .isEqualTo("stratfy/nora");
    }

    /** Captures issue creations in memory (replaces the real HTTP calls to GitHub). */
    static class RecordingGitHub extends GitHubClient {
        record Issue(String token, String repo, String title, String body, List<String> labels) {}

        final List<Issue> issues = new ArrayList<>();

        RecordingGitHub() {
            super(new ObjectMapper());
        }

        @Override
        public String createIssue(
                String accessToken, String repo, String title, String body, List<String> labels) {
            issues.add(new Issue(accessToken, repo, title, body, labels));
            return "https://github.com/" + repo + "/issues/" + issues.size();
        }
    }

    /** Meeting context shared by the wave 1 action tests. */
    static final class TestContexts {
        static final UUID TENANT = UUID.randomUUID();

        private TestContexts() {}

        static WorkflowEventContext context(
                String title, String summary, List<WorkflowEventContext.ActionItemView> items) {
            UUID meetingId = UUID.randomUUID();
            return new WorkflowEventContext(
                    TENANT,
                    "meeting.analysis_completed",
                    meetingId,
                    title,
                    List.of("flows"),
                    summary,
                    summary,
                    1,
                    items.size(),
                    0,
                    items,
                    70,
                    65,
                    "http://localhost:3000/meetings/" + meetingId,
                    OffsetDateTime.now(ZoneOffset.UTC),
                    false);
        }
    }
}

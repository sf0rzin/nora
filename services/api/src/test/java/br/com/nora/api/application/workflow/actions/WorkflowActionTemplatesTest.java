package br.com.nora.api.application.workflow.actions;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.workflow.WorkflowEventContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkflowActionTemplatesTest {

    private static WorkflowEventContext ctx(String summary) {
        return new WorkflowEventContext(
                UUID.randomUUID(),
                "meeting.analysis_completed",
                UUID.randomUUID(),
                "Renovação — Acme & Cia",
                List.of("renovação"),
                summary,
                summary,
                2,
                3,
                1,
                List.of(
                        new WorkflowEventContext.ActionItemView(
                                "Enviar proposta", "Ana", "HIGH", LocalDate.of(2026, 6, 20)),
                        new WorkflowEventContext.ActionItemView(
                                "Agendar follow-up", null, "MEDIUM", null)),
                82,
                74,
                "https://nora.systems/meetings/abc",
                OffsetDateTime.of(2026, 6, 12, 10, 0, 0, 0, ZoneOffset.UTC),
                false);
    }

    @Test
    void corpoPadraoRenderizaMarkdownDoResumoSemAsteriscosLiterais() {
        String html =
                WorkflowActionTemplates.bodyOrDefault(
                        Map.of(), ctx("A **Acme** pediu urgência.\n\n- Proposta\n- Contrato"));

        assertThat(html).contains("<strong>Acme</strong>");
        assertThat(html).doesNotContain("**");
        assertThat(html).contains("Renovação — Acme &amp; Cia");
        // Moldura: cabeçalho NORA, métricas, próximos passos, CTA e rodapé.
        assertThat(html).contains(">NORA</span>");
        assertThat(html).contains("Action items");
        assertThat(html).contains("Próximos passos");
        assertThat(html).contains("Enviar proposta");
        assertThat(html).contains("https://nora.systems/meetings/abc");
        assertThat(html).contains("Abrir no NORA");
    }

    @Test
    void corpoCustomAplicaPlaceholdersERenderizaMarkdown() {
        String html =
                WorkflowActionTemplates.bodyOrDefault(
                        Map.of(
                                "body",
                                "Olá! Resumo de **{{meeting.title}}**:\n\n{{meeting.summary}}"),
                        ctx("- Ponto um\n- Ponto dois"));

        assertThat(html).contains("<strong>Renovação — Acme &amp; Cia</strong>");
        assertThat(html).contains("Ponto um");
        assertThat(html).doesNotContain("{{meeting");
        assertThat(html).doesNotContain("**");
    }

    @Test
    void execucaoDeTesteGanhaAvisoDeDadosDeExemplo() {
        WorkflowEventContext sample =
                new WorkflowEventContext(
                        UUID.randomUUID(),
                        "meeting.analysis_completed",
                        UUID.randomUUID(),
                        "Reunião de exemplo",
                        List.of(),
                        "resumo",
                        "resumo",
                        0,
                        0,
                        0,
                        List.of(),
                        null,
                        null,
                        null,
                        OffsetDateTime.of(2026, 6, 12, 10, 0, 0, 0, ZoneOffset.UTC),
                        true);

        String html = WorkflowActionTemplates.bodyOrDefault(Map.of(), sample);

        assertThat(html).contains("Execução de teste com dados de exemplo");
        assertThat(html).doesNotContain("Abrir no NORA");
    }
}

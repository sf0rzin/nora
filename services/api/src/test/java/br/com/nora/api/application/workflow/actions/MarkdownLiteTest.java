package br.com.nora.api.application.workflow.actions;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MarkdownLiteTest {

    @Test
    void negritoListaETituloViramHtmlSemAsteriscosLiterais() {
        String md =
                """
                ## Resumo da reunião

                A **Acme** sinalizou *urgência* na renovação.

                - Alinhar proposta com o jurídico
                - Enviar contrato até sexta
                """;

        String html = MarkdownLite.render(md);

        assertThat(html).contains("<h3", ">Resumo da reunião</h3>");
        assertThat(html).contains("<strong>Acme</strong>");
        assertThat(html).contains("<em>urgência</em>");
        assertThat(html).contains("<ul", "<li", "Alinhar proposta com o jurídico");
        assertThat(html).doesNotContain("**").doesNotContain("## ");
    }

    @Test
    void listaNumeradaViraOrdenada() {
        String html = MarkdownLite.render("1. Primeiro\n2. Segundo");
        assertThat(html).contains("<ol", "<li").contains("Primeiro").contains("Segundo");
    }

    @Test
    void htmlDoConteudoEEscapado() {
        String html = MarkdownLite.render("Cuidado com <script>alert(1)</script> aqui");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void linkMarkdownEUrlSoltaViramAncora() {
        String html =
                MarkdownLite.render(
                        "Veja [a reunião](https://nora.systems/m/1) ou https://nora.systems/docs");
        assertThat(html)
                .contains("<a href=\"https://nora.systems/m/1\"")
                .contains(">a reunião</a>")
                .contains("<a href=\"https://nora.systems/docs\"");
    }

    @Test
    void paragrafosSeparadosPorLinhaEmBrancoEQuebraSimplesViraBr() {
        String html = MarkdownLite.render("linha um\nlinha dois\n\noutro parágrafo");
        assertThat(html).contains("linha um<br>linha dois");
        assertThat(html).contains("outro parágrafo");
    }

    @Test
    void vazioOuNuloRendeVazio() {
        assertThat(MarkdownLite.render(null)).isEmpty();
        assertThat(MarkdownLite.render("  ")).isEmpty();
    }
}

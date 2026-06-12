package br.com.nora.api.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.workflow.WorkflowDefinition.NodeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionParserTest {

    private final WorkflowDefinitionParser parser =
            new WorkflowDefinitionParser(new ObjectMapper());
    private final Set<String> actions = Set.of("send_email", "slack_post_message");

    private static final String VALID =
            """
            {
              "nodes": [
                {"id":"t1","kind":"trigger","type":"meeting.analysis_completed",
                 "position":{"x":40,"y":120}},
                {"id":"c1","kind":"condition","type":"productivity_score_below",
                 "params":{"value":70},"position":{"x":200,"y":120}},
                {"id":"a1","kind":"action","type":"send_email",
                 "params":{"to":"x@nora.dev"},"position":{"x":360,"y":120}}
              ],
              "edges": [
                {"id":"e1","source":"t1","target":"c1"},
                {"id":"e2","source":"c1","target":"a1"}
              ]
            }
            """;

    @Test
    void parseValido_montaGrafoNavegavel() {
        WorkflowDefinition def = parser.parse(VALID, actions);
        assertThat(def.nodes()).hasSize(3);
        assertThat(def.triggerNode().type()).isEqualTo("meeting.analysis_completed");
        assertThat(def.childrenOf("t1"))
                .extracting(WorkflowDefinition.Node::id)
                .containsExactly("c1");
        assertThat(def.childrenOf("c1"))
                .extracting(WorkflowDefinition.Node::id)
                .containsExactly("a1");
        assertThat(def.nodesById().get("a1").kind()).isEqualTo(NodeKind.ACTION);
        assertThat(def.nodesById().get("c1").params()).containsEntry("value", 70);
        assertThat(def.hasCycle()).isFalse();
    }

    @Test
    void rejeitaJsonInvalido() {
        assertThatThrownBy(() -> parser.parse("nao é json", actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class);
    }

    @Test
    void rejeitaSemGatilho() {
        String json =
                """
                {"nodes":[{"id":"a1","kind":"action","type":"send_email",
                "params":{"to":"x@nora.dev"}}],"edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("exatamente um gatilho");
    }

    @Test
    void rejeitaDoisGatilhos() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"t2","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("exatamente um gatilho");
    }

    @Test
    void rejeitaGatilhoDesconhecido() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.exploded"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("gatilho desconhecido");
    }

    @Test
    void rejeitaSemAcao() {
        String json =
                """
                {"nodes":[{"id":"t1","kind":"trigger","type":"meeting.analysis_completed"}],
                 "edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("ao menos uma ação");
    }

    @Test
    void rejeitaAcaoDesconhecida() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"launch_rocket"}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("ação desconhecida");
    }

    @Test
    void rejeitaSendEmailSemDestinatario() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"subject":"oi"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("destinatário");
    }

    @Test
    void rejeitaSlackPostMessageSemCanal() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"slack_post_message","params":{"text":"oi"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.channel");
    }

    @Test
    void aceitaSlackPostMessageComCanal() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"slack_post_message",
                   "params":{"channel":"#vendas"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThat(parser.parse(json, actions).nodesById().get("a1").params())
                .containsEntry("channel", "#vendas");
    }

    @Test
    void rejeitaGithubCreateIssueSemRepoOuForaDoFormato() {
        Set<String> comGithub = Set.of("github_create_issue");
        String semRepo =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"github_create_issue","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(semRepo, comGithub))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.repo");

        String semBarra =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"github_create_issue",
                   "params":{"repo":"sem-barra"}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(semBarra, comGithub))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("owner/nome");
    }

    @Test
    void rejeitaNotionCreatePageSemPaginaPai() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"notion_create_page","params":{}}],
                 "edges":[{"id":"e1","source":"t1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, Set.of("notion_create_page")))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("params.parentPageId");
    }

    @Test
    void rejeitaArestaParaNoInexistente() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"fantasma"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("nó inexistente");
    }

    @Test
    void rejeitaCiclo() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}},
                  {"id":"a2","kind":"action","type":"send_email","params":{"to":"y@nora.dev"}}],
                 "edges":[
                  {"id":"e1","source":"t1","target":"a1"},
                  {"id":"e2","source":"a1","target":"a2"},
                  {"id":"e3","source":"a2","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("ciclos");
    }

    @Test
    void rejeitaIdDuplicado() {
        String json =
                """
                {"nodes":[
                  {"id":"n1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"n1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("duplicado");
    }

    @Test
    void rejeitaCondicaoDesconhecida() {
        String json =
                """
                {"nodes":[
                  {"id":"t1","kind":"trigger","type":"meeting.analysis_completed"},
                  {"id":"c1","kind":"condition","type":"fase_da_lua"},
                  {"id":"a1","kind":"action","type":"send_email","params":{"to":"x@nora.dev"}}],
                 "edges":[{"id":"e1","source":"t1","target":"c1"},
                          {"id":"e2","source":"c1","target":"a1"}]}
                """;
        assertThatThrownBy(() -> parser.parse(json, actions))
                .isInstanceOf(WorkflowException.InvalidDefinition.class)
                .hasMessageContaining("condição desconhecida");
    }
}

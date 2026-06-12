package br.com.nora.api.application.workflow;

import br.com.nora.api.application.workflow.WorkflowDefinition.Edge;
import br.com.nora.api.application.workflow.WorkflowDefinition.Node;
import br.com.nora.api.application.workflow.WorkflowDefinition.NodeKind;
import br.com.nora.api.domain.workflow.TriggerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Parse + validação do {@code definition_json} de um workflow (formato do canvas: nodes + edges).
 * Toda violação vira {@link WorkflowException.InvalidDefinition} com mensagem acionável — é o que a
 * sidebar do canvas mostra ao salvar.
 *
 * <p>Formato esperado:
 *
 * <pre>{@code
 * {
 *   "nodes": [
 *     {"id":"n1","kind":"trigger","type":"meeting.analysis_completed","position":{"x":0,"y":0}},
 *     {"id":"n2","kind":"condition","type":"productivity_score_below","params":{"value":70}},
 *     {"id":"n3","kind":"action","type":"send_email","params":{"to":"a@b.c"}}
 *   ],
 *   "edges": [{"id":"e1","source":"n1","target":"n2"}, {"id":"e2","source":"n2","target":"n3"}]
 * }
 * }</pre>
 */
@Component
public class WorkflowDefinitionParser {

    /** Condições conhecidas pelo {@link ConditionEvaluator}. */
    public static final Set<String> CONDITION_TYPES =
            Set.of(
                    "productivity_score_below",
                    "customer_confidence_below",
                    "tag_equals",
                    "priority_equals");

    private final ObjectMapper mapper;

    public WorkflowDefinitionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parse + validação estrutural. {@code knownActionTypes} vem do registry de {@link
     * ActionExecutor} — ações desconhecidas são rejeitadas no save, não na execução.
     */
    public WorkflowDefinition parse(String definitionJson, Set<String> knownActionTypes) {
        JsonNode root;
        try {
            root = mapper.readTree(definitionJson);
        } catch (Exception ex) {
            throw new WorkflowException.InvalidDefinition("definition não é JSON válido");
        }
        if (root == null || !root.isObject()) {
            throw new WorkflowException.InvalidDefinition("definition precisa ser um objeto JSON");
        }
        List<Node> nodes = parseNodes(root.path("nodes"));
        List<Edge> edges = parseEdges(root.path("edges"));
        WorkflowDefinition definition = new WorkflowDefinition(nodes, edges);
        validate(definition, knownActionTypes);
        return definition;
    }

    /** Serialização canônica (compacta) para persistir. */
    public String canonicalJson(JsonNode definition) {
        try {
            return mapper.writeValueAsString(definition);
        } catch (Exception ex) {
            throw new WorkflowException.InvalidDefinition("definition não pôde ser serializada");
        }
    }

    private List<Node> parseNodes(JsonNode nodesJson) {
        if (!nodesJson.isArray() || nodesJson.isEmpty()) {
            throw new WorkflowException.InvalidDefinition(
                    "definition precisa de ao menos um nó (nodes)");
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode n : nodesJson) {
            String id = textOrNull(n, "id");
            if (id == null || id.isBlank()) {
                throw new WorkflowException.InvalidDefinition("todo nó precisa de id");
            }
            NodeKind kind;
            try {
                kind = NodeKind.fromWire(textOrNull(n, "kind"));
            } catch (IllegalArgumentException ex) {
                throw new WorkflowException.InvalidDefinition(
                        "nó '" + id + "': kind inválido (use trigger, condition ou action)");
            }
            String type = textOrNull(n, "type");
            if (type == null || type.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "nó '" + id + "': type é obrigatório");
            }
            Map<String, Object> params = toParams(n.path("params"));
            JsonNode pos = n.path("position");
            double x = pos.path("x").asDouble(0);
            double y = pos.path("y").asDouble(0);
            nodes.add(new Node(id, kind, type.trim(), params, x, y));
        }
        return nodes;
    }

    private List<Edge> parseEdges(JsonNode edgesJson) {
        List<Edge> edges = new ArrayList<>();
        if (edgesJson.isMissingNode() || edgesJson.isNull()) {
            return edges;
        }
        if (!edgesJson.isArray()) {
            throw new WorkflowException.InvalidDefinition("edges precisa ser uma lista");
        }
        int i = 0;
        for (JsonNode e : edgesJson) {
            String id = textOrNull(e, "id");
            String source = textOrNull(e, "source");
            String target = textOrNull(e, "target");
            if (source == null || target == null) {
                throw new WorkflowException.InvalidDefinition(
                        "toda aresta precisa de source e target");
            }
            edges.add(new Edge(id == null ? "e" + (++i) : id, source, target));
        }
        return edges;
    }

    private void validate(WorkflowDefinition definition, Set<String> knownActionTypes) {
        Set<String> ids = new HashSet<>();
        for (Node n : definition.nodes()) {
            if (!ids.add(n.id())) {
                throw new WorkflowException.InvalidDefinition("id de nó duplicado: " + n.id());
            }
        }

        List<Node> triggers =
                definition.nodes().stream().filter(n -> n.kind() == NodeKind.TRIGGER).toList();
        if (triggers.size() != 1) {
            throw new WorkflowException.InvalidDefinition(
                    "o fluxo precisa de exatamente um gatilho (encontrei " + triggers.size() + ")");
        }
        try {
            TriggerType.fromWire(triggers.get(0).type());
        } catch (IllegalArgumentException ex) {
            throw new WorkflowException.InvalidDefinition(
                    "gatilho desconhecido: " + triggers.get(0).type());
        }

        for (Edge e : definition.edges()) {
            if (!ids.contains(e.source()) || !ids.contains(e.target())) {
                throw new WorkflowException.InvalidDefinition(
                        "aresta '" + e.id() + "' referencia nó inexistente");
            }
        }
        if (definition.hasCycle()) {
            throw new WorkflowException.InvalidDefinition("o fluxo não pode ter ciclos");
        }

        boolean hasAction = false;
        for (Node n : definition.nodes()) {
            if (n.kind() == NodeKind.CONDITION && !CONDITION_TYPES.contains(n.type())) {
                throw new WorkflowException.InvalidDefinition("condição desconhecida: " + n.type());
            }
            if (n.kind() == NodeKind.ACTION) {
                hasAction = true;
                if (!knownActionTypes.contains(n.type())) {
                    throw new WorkflowException.InvalidDefinition("ação desconhecida: " + n.type());
                }
                validateActionParams(n);
            }
        }
        if (!hasAction) {
            throw new WorkflowException.InvalidDefinition(
                    "o fluxo precisa de ao menos uma ação ligada ao gatilho");
        }
    }

    private void validateActionParams(Node action) {
        if ("send_email".equals(action.type()) || "gmail_send_email".equals(action.type())) {
            String to = action.paramAsString("to");
            if (to == null || to.isBlank() || !to.contains("@")) {
                throw new WorkflowException.InvalidDefinition(
                        "ação 'Enviar e-mail' (nó '"
                                + action.id()
                                + "') precisa de um destinatário válido em params.to");
            }
        }
        if ("slack_post_message".equals(action.type())) {
            String channel = action.paramAsString("channel");
            if (channel == null || channel.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "ação 'Postar no Slack' (nó '"
                                + action.id()
                                + "') precisa do canal em params.channel (ex.: #vendas)");
            }
        }
        if ("github_create_issue".equals(action.type())) {
            String repo = action.paramAsString("repo");
            if (repo == null || repo.isBlank() || !repo.contains("/")) {
                throw new WorkflowException.InvalidDefinition(
                        "ação 'Criar issue no GitHub' (nó '"
                                + action.id()
                                + "') precisa do repositório em params.repo no formato owner/nome"
                                + " (ex.: stratfy/nora)");
            }
        }
        if ("notion_create_page".equals(action.type())) {
            String parentPageId = action.paramAsString("parentPageId");
            if (parentPageId == null || parentPageId.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "ação 'Criar página no Notion' (nó '"
                                + action.id()
                                + "') precisa da página pai em params.parentPageId");
            }
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private Map<String, Object> toParams(JsonNode params) {
        if (params.isMissingNode() || params.isNull()) {
            return Map.of();
        }
        if (!params.isObject()) {
            throw new WorkflowException.InvalidDefinition("params de nó precisa ser um objeto");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        params.fields()
                .forEachRemaining(
                        entry -> {
                            JsonNode v = entry.getValue();
                            if (v.isNumber()) {
                                result.put(entry.getKey(), v.numberValue());
                            } else if (v.isBoolean()) {
                                result.put(entry.getKey(), v.booleanValue());
                            } else if (v.isTextual()) {
                                result.put(entry.getKey(), v.asText());
                            } else {
                                result.put(entry.getKey(), v.toString());
                            }
                        });
        return result;
    }
}

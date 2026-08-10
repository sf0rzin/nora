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
 * Parsing + validation of a workflow's {@code definition_json} (canvas format: nodes + edges).
 * Every violation becomes a {@link WorkflowException.InvalidDefinition} with an actionable message
 * — it is what the canvas sidebar shows on save.
 *
 * <p>Expected format:
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

    /** Accepted prefixes for a Discord channel webhook (same rule as the executor). */
    private static final List<String> DISCORD_WEBHOOK_PREFIXES =
            List.of("https://discord.com/api/webhooks/", "https://discordapp.com/api/webhooks/");

    /** Conditions known to the {@link ConditionEvaluator}. */
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
     * Parsing + structural validation. {@code knownActionTypes} comes from the {@link
     * ActionExecutor} registry — unknown actions are rejected on save, not at execution time.
     */
    public WorkflowDefinition parse(String definitionJson, Set<String> knownActionTypes) {
        JsonNode root;
        try {
            root = mapper.readTree(definitionJson);
        } catch (Exception ex) {
            throw new WorkflowException.InvalidDefinition("definition is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new WorkflowException.InvalidDefinition("definition must be a JSON object");
        }
        List<Node> nodes = parseNodes(root.path("nodes"));
        List<Edge> edges = parseEdges(root.path("edges"));
        WorkflowDefinition definition = new WorkflowDefinition(nodes, edges);
        validate(definition, knownActionTypes);
        return definition;
    }

    /** Canonical (compact) serialization for persisting. */
    public String canonicalJson(JsonNode definition) {
        try {
            return mapper.writeValueAsString(definition);
        } catch (Exception ex) {
            throw new WorkflowException.InvalidDefinition("definition could not be serialized");
        }
    }

    private List<Node> parseNodes(JsonNode nodesJson) {
        if (!nodesJson.isArray() || nodesJson.isEmpty()) {
            throw new WorkflowException.InvalidDefinition(
                    "definition needs at least one node (nodes)");
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode n : nodesJson) {
            String id = textOrNull(n, "id");
            if (id == null || id.isBlank()) {
                throw new WorkflowException.InvalidDefinition("every node needs an id");
            }
            NodeKind kind;
            try {
                kind = NodeKind.fromWire(textOrNull(n, "kind"));
            } catch (IllegalArgumentException ex) {
                throw new WorkflowException.InvalidDefinition(
                        "node '" + id + "': invalid kind (use trigger, condition or action)");
            }
            String type = textOrNull(n, "type");
            if (type == null || type.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "node '" + id + "': type is required");
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
            throw new WorkflowException.InvalidDefinition("edges must be a list");
        }
        int i = 0;
        for (JsonNode e : edgesJson) {
            String id = textOrNull(e, "id");
            String source = textOrNull(e, "source");
            String target = textOrNull(e, "target");
            if (source == null || target == null) {
                throw new WorkflowException.InvalidDefinition("every edge needs source and target");
            }
            edges.add(new Edge(id == null ? "e" + (++i) : id, source, target));
        }
        return edges;
    }

    private void validate(WorkflowDefinition definition, Set<String> knownActionTypes) {
        Set<String> ids = new HashSet<>();
        for (Node n : definition.nodes()) {
            if (!ids.add(n.id())) {
                throw new WorkflowException.InvalidDefinition("duplicate node id: " + n.id());
            }
        }

        List<Node> triggers =
                definition.nodes().stream().filter(n -> n.kind() == NodeKind.TRIGGER).toList();
        if (triggers.size() != 1) {
            throw new WorkflowException.InvalidDefinition(
                    "the flow needs exactly one trigger (found " + triggers.size() + ")");
        }
        try {
            TriggerType.fromWire(triggers.get(0).type());
        } catch (IllegalArgumentException ex) {
            throw new WorkflowException.InvalidDefinition(
                    "unknown trigger: " + triggers.get(0).type());
        }

        for (Edge e : definition.edges()) {
            if (!ids.contains(e.source()) || !ids.contains(e.target())) {
                throw new WorkflowException.InvalidDefinition(
                        "edge '" + e.id() + "' references a nonexistent node");
            }
        }
        if (definition.hasCycle()) {
            throw new WorkflowException.InvalidDefinition("the flow cannot have cycles");
        }

        boolean hasAction = false;
        for (Node n : definition.nodes()) {
            if (n.kind() == NodeKind.CONDITION && !CONDITION_TYPES.contains(n.type())) {
                throw new WorkflowException.InvalidDefinition("unknown condition: " + n.type());
            }
            if (n.kind() == NodeKind.ACTION) {
                hasAction = true;
                if (!knownActionTypes.contains(n.type())) {
                    throw new WorkflowException.InvalidDefinition("unknown action: " + n.type());
                }
                validateActionParams(n);
            }
        }
        if (!hasAction) {
            throw new WorkflowException.InvalidDefinition(
                    "the flow needs at least one action wired to the trigger");
        }
    }

    private void validateActionParams(Node action) {
        if ("send_email".equals(action.type())
                || "gmail_send_email".equals(action.type())
                || "outlook_send_email".equals(action.type())) {
            String to = action.paramAsString("to");
            if (to == null || to.isBlank() || !to.contains("@")) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Send e-mail' (node '"
                                + action.id()
                                + "') needs a valid recipient in params.to");
            }
        }
        if ("slack_post_message".equals(action.type())) {
            String channel = action.paramAsString("channel");
            if (channel == null || channel.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Post to Slack' (node '"
                                + action.id()
                                + "') needs the channel in params.channel (e.g. #sales)");
            }
        }
        if ("github_create_issue".equals(action.type())) {
            String repo = action.paramAsString("repo");
            if (repo == null || repo.isBlank() || !repo.contains("/")) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Create GitHub issue' (node '"
                                + action.id()
                                + "') needs the repository in params.repo in owner/name format"
                                + " (e.g. stratfy/nora)");
            }
        }
        if ("trello_create_card".equals(action.type())) {
            String listId = action.paramAsString("listId");
            if (listId == null || listId.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Create Trello cards' (node '"
                                + action.id()
                                + "') needs the list in params.listId (board list id)");
            }
        }
        if ("notion_create_page".equals(action.type())) {
            String parentPageId = action.paramAsString("parentPageId");
            if (parentPageId == null || parentPageId.isBlank()) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Create Notion page' (node '"
                                + action.id()
                                + "') needs the parent page in params.parentPageId");
            }
        }
        if ("call_webhook".equals(action.type())) {
            String url = action.paramAsString("url");
            if (url == null || url.isBlank() || !url.trim().startsWith("https://")) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Call webhook' (node '"
                                + action.id()
                                + "') needs an https:// URL in params.url");
            }
        }
        if ("discord_post_message".equals(action.type())) {
            String url = action.paramAsString("webhookUrl");
            String trimmed = url == null ? "" : url.trim();
            if (DISCORD_WEBHOOK_PREFIXES.stream().noneMatch(trimmed::startsWith)) {
                throw new WorkflowException.InvalidDefinition(
                        "action 'Notify Discord' (node '"
                                + action.id()
                                + "') needs the channel webhook URL in params.webhookUrl"
                                + " (starts with https://discord.com/api/webhooks/)");
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
            throw new WorkflowException.InvalidDefinition("node params must be an object");
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

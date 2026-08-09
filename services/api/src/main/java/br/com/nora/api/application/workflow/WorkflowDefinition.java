package br.com.nora.api.application.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Executable graph of a workflow, as drawn on the canvas: nodes (trigger/condition/action) linked
 * by directed edges. It is the in-memory model of the {@code definition_json} — the
 * parsing/validation lives in {@link WorkflowDefinitionParser}.
 */
public record WorkflowDefinition(List<Node> nodes, List<Edge> edges) {

    public enum NodeKind {
        TRIGGER,
        CONDITION,
        ACTION;

        public static NodeKind fromWire(String raw) {
            if (raw != null) {
                for (NodeKind k : values()) {
                    if (k.name().equalsIgnoreCase(raw.trim())) {
                        return k;
                    }
                }
            }
            throw new IllegalArgumentException("unknown node kind: " + raw);
        }
    }

    /**
     * Graph node. {@code type} is the semantic identifier within the kind (e.g. {@code
     * meeting.analysis_completed} for trigger, {@code productivity_score_below} for condition,
     * {@code send_email} for action). {@code params} carries the sidebar configuration; {@code x}/
     * {@code y} are only canvas layout (the engine ignores them).
     */
    public record Node(
            String id, NodeKind kind, String type, Map<String, Object> params, double x, double y) {

        public Node {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public String paramAsString(String key) {
            Object value = params.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    public record Edge(String id, String source, String target) {}

    /** The graph's single trigger node (the parser's validation guarantees exactly one). */
    public Node triggerNode() {
        return nodes.stream()
                .filter(n -> n.kind() == NodeKind.TRIGGER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("definition without trigger node"));
    }

    /** Target nodes of the edges leaving {@code nodeId}, in edge order. */
    public List<Node> childrenOf(String nodeId) {
        Map<String, Node> byId = nodesById();
        List<Node> children = new ArrayList<>();
        for (Edge e : edges) {
            if (e.source().equals(nodeId)) {
                Node child = byId.get(e.target());
                if (child != null) {
                    children.add(child);
                }
            }
        }
        return children;
    }

    public Map<String, Node> nodesById() {
        Map<String, Node> byId = new HashMap<>();
        for (Node n : nodes) {
            byId.put(n.id(), n);
        }
        return byId;
    }

    /** Detects a cycle via iterative DFS (the graph must be acyclic for the engine to finish). */
    public boolean hasCycle() {
        Map<String, List<String>> adj = new HashMap<>();
        for (Edge e : edges) {
            adj.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e.target());
        }
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (Node n : nodes) {
            if (visited.contains(n.id())) {
                continue;
            }
            Deque<String> stack = new ArrayDeque<>();
            Deque<Integer> childIndex = new ArrayDeque<>();
            stack.push(n.id());
            childIndex.push(0);
            inStack.add(n.id());
            while (!stack.isEmpty()) {
                String current = stack.peek();
                List<String> children = adj.getOrDefault(current, List.of());
                int idx = childIndex.pop();
                if (idx < children.size()) {
                    childIndex.push(idx + 1);
                    String next = children.get(idx);
                    if (inStack.contains(next)) {
                        return true;
                    }
                    if (!visited.contains(next)) {
                        stack.push(next);
                        childIndex.push(0);
                        inStack.add(next);
                    }
                } else {
                    visited.add(current);
                    inStack.remove(current);
                    stack.pop();
                }
            }
        }
        return false;
    }
}

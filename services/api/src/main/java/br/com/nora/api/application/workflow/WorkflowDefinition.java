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
 * Grafo executável de um workflow, como desenhado no canvas: nós (gatilho/condição/ação) ligados
 * por arestas direcionadas. É o modelo em memória do {@code definition_json} — o parse/validação
 * vive no {@link WorkflowDefinitionParser}.
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
     * Nó do grafo. {@code type} é o identificador semântico dentro do kind (ex.: {@code
     * meeting.analysis_completed} para gatilho, {@code productivity_score_below} para condição,
     * {@code send_email} para ação). {@code params} carrega a configuração da sidebar; {@code x}/
     * {@code y} são só layout do canvas (o engine ignora).
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

    /** O único nó de gatilho do grafo (a validação do parser garante exatamente um). */
    public Node triggerNode() {
        return nodes.stream()
                .filter(n -> n.kind() == NodeKind.TRIGGER)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("definition without trigger node"));
    }

    /** Nós de destino das arestas que saem de {@code nodeId}, na ordem das arestas. */
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

    /** Detecta ciclo por DFS iterativo (grafo precisa ser acíclico para o engine terminar). */
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

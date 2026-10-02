package com.example.orchestrator.graph;

import com.example.orchestrator.model.NodeSpec;

import java.util.*;

/** Explicit dependency graph: validation, topological layers, descendants, data-flow consumers. */
public final class WorkflowGraph {
    private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

    public WorkflowGraph(List<NodeSpec> specs) {
        for (NodeSpec n : specs) {
            if (n.id == null || n.id.isBlank()) throw new IllegalArgumentException("node without id");
            if (nodes.put(n.id, n) != null) throw new IllegalArgumentException("duplicate node id: " + n.id);
        }
        for (NodeSpec n : specs)
            for (String d : n.dependsOn)
                if (!nodes.containsKey(d))
                    throw new IllegalArgumentException("node " + n.id + " depends on unknown node " + d);
        layers(); // throws on cycle
    }

    public Collection<NodeSpec> nodes() { return nodes.values(); }

    public NodeSpec node(String id) {
        NodeSpec n = nodes.get(id);
        if (n == null) throw new NoSuchElementException("unknown node " + id);
        return n;
    }

    public boolean contains(String id) { return nodes.containsKey(id); }

    /** Topological layers (Kahn). Nodes in one layer are mutually independent and may run in parallel. */
    public List<List<String>> layers() {
        Map<String, Integer> indeg = new LinkedHashMap<>();
        nodes.values().forEach(n -> indeg.put(n.id, n.dependsOn.size()));
        List<List<String>> out = new ArrayList<>();
        int seen = 0;
        while (seen < nodes.size()) {
            List<String> layer = new ArrayList<>();
            for (var e : indeg.entrySet()) if (e.getValue() == 0) layer.add(e.getKey());
            if (layer.isEmpty()) throw new IllegalArgumentException("cycle detected in workflow graph");
            for (String id : layer) {
                indeg.put(id, -1);
                for (NodeSpec n : nodes.values())
                    if (n.dependsOn.contains(id)) indeg.computeIfPresent(n.id, (k, v) -> v - 1);
            }
            out.add(layer);
            seen += layer.size();
        }
        return out;
    }

    /** Join (synchronisation) nodes: more than one upstream dependency. */
    public List<String> joins() {
        return nodes.values().stream().filter(n -> n.dependsOn.size() > 1).map(n -> n.id).toList();
    }

    public Set<String> descendants(String id) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> q = new ArrayDeque<>(List.of(id));
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (NodeSpec n : nodes.values())
                if (n.dependsOn.contains(cur) && out.add(n.id)) q.add(n.id);
        }
        return out;
    }

    /** Nodes whose declared inputs include any of the given artifact ids. */
    public Set<String> consumersOf(Collection<String> artifactIds) {
        Set<String> out = new LinkedHashSet<>();
        for (NodeSpec n : nodes.values())
            for (String c : n.consumes) if (artifactIds.contains(c)) out.add(n.id);
        return out;
    }

    /** Data-flow closure: consumers of changed artifacts plus consumers of anything those nodes produce. */
    public Set<String> dirtyClosure(Collection<String> changedArtifacts) {
        Set<String> dirty = new LinkedHashSet<>();
        Set<String> frontier = new LinkedHashSet<>(changedArtifacts);
        while (!frontier.isEmpty()) {
            Set<String> next = new LinkedHashSet<>();
            for (String id : consumersOf(frontier)) {
                if (dirty.add(id)) next.addAll(node(id).produces);
            }
            frontier = next;
        }
        return dirty;
    }

    public String toMermaid() {
        StringBuilder sb = new StringBuilder("flowchart TD\n");
        for (NodeSpec n : nodes.values()) {
            String label = n.id + (n.humanGate ? "<br/>HUMAN GATE" : "");
            sb.append("  ").append(n.id).append("[\"").append(label).append("\"]\n");
        }
        for (NodeSpec n : nodes.values())
            for (String d : n.dependsOn) sb.append("  ").append(d).append(" --> ").append(n.id).append('\n');
        return sb.toString();
    }
}

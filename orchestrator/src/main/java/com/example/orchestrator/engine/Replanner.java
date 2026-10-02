package com.example.orchestrator.engine;

import com.example.orchestrator.graph.WorkflowGraph;
import com.example.orchestrator.model.NodeState;
import com.example.orchestrator.model.NodeStatus;
import com.example.orchestrator.resilience.SnapshotStore;

import java.io.IOException;
import java.util.*;

/**
 * Dynamic re-planning. When upstream artifacts change, only the data-flow dependants are invalidated:
 * their file changes are rolled back (newest first) and they become schedulable again. Unaffected completed
 * nodes keep their results.
 */
public final class Replanner {
    public record Outcome(Set<String> dirty, List<String> rolledBack, List<String> invalidated, List<String> retained) {}

    public Outcome replan(WorkflowGraph graph, Collection<String> changedArtifacts, Map<String, NodeState> states,
                          SnapshotStore snapshots) throws IOException {
        Set<String> dirty = graph.dirtyClosure(changedArtifacts);
        List<String> order = snapshots.completionOrder();
        Collections.reverse(order);
        List<String> rolledBack = new ArrayList<>();
        for (String n : order) {
            if (dirty.contains(n) && states.get(n).status == NodeStatus.DONE) {
                snapshots.rollbackCommitted(n);
                rolledBack.add(n);
            }
        }
        List<String> invalidated = new ArrayList<>();
        for (String n : dirty) {
            NodeState s = states.get(n);
            if (s.status == NodeStatus.DONE || s.status == NodeStatus.FAILED) {
                s.status = NodeStatus.INVALIDATED;
                s.attempts = 0;
                s.usedFallback = false;
                s.lastError = null;
                invalidated.add(n);
            }
        }
        List<String> retained = states.entrySet().stream()
                .filter(e -> e.getValue().status == NodeStatus.DONE && !dirty.contains(e.getKey()))
                .map(Map.Entry::getKey).toList();
        return new Outcome(dirty, rolledBack, invalidated, retained);
    }
}

package com.example.orchestrator.model;

import java.util.ArrayList;
import java.util.List;

public class NodeState {
    public NodeStatus status = NodeStatus.PENDING;
    public int attempts;
    public int runs; // times the node reached DONE (re-runs after replan)
    public boolean usedFallback;
    public int rollbacks;
    public long startedAtMs;
    public long endedAtMs;
    public String lastError;
    public List<String> notes = new ArrayList<>();
}

package com.example.orchestrator.engine;

import com.example.orchestrator.model.NodeState;

import java.util.Map;

public record RunResult(String status, String haltReason, Map<String, Object> metrics, Map<String, NodeState> nodes) {
    public boolean completed() { return "COMPLETED".equals(status); }
}

package com.example.orchestrator.model;

import java.util.List;
import java.util.Map;

/** Declarative node of the workflow graph (loaded from scenario YAML). */
public class NodeSpec {
    public String id;
    public String agent;
    public String fallbackAgent;
    public List<String> dependsOn = List.of();
    public List<String> consumes = List.of();
    public List<String> produces = List.of();
    public boolean humanGate;
    public String gateReason;
    public List<String> exitGate = List.of();
    public Map<String, String> params = Map.of();
    public int maxRetries = -1; // -1 = engine default
}

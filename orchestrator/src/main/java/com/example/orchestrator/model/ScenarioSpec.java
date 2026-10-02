package com.example.orchestrator.model;

import java.util.List;
import java.util.Map;

public class ScenarioSpec {
    public String name;
    public String description;
    public String requirement;
    public String version = "1.0.0";
    public List<NodeSpec> nodes = List.of();
    public List<EventSpec> events = List.of();
    public Map<String, String> approvals = Map.of();
    public List<FaultSpec> faults = List.of();

    /** External event (for example a stakeholder clarification) injected after a node completes. */
    public static class EventSpec {
        public String afterNode;
        public String type;
        public Map<String, String> answers = Map.of();
    }

    /** Fault injection: fail the first {@code times} executions of the node's agent. */
    public static class FaultSpec {
        public String node;
        public int times = 1;
        public String mode = "exception"; // exception | bad-output | policy
        public boolean includeFallback = false;
    }
}

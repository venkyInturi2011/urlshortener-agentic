package com.example.orchestrator.agent;

import java.util.HashMap;
import java.util.Map;

public final class AgentRegistry {
    private final Map<String, Agent> byName = new HashMap<>();

    public AgentRegistry register(Agent a) {
        byName.put(a.name(), a);
        return this;
    }

    public Agent get(String name) {
        Agent a = byName.get(name);
        if (a == null) throw new IllegalArgumentException("unknown agent: " + name);
        return a;
    }

    public static AgentRegistry defaults() {
        return new AgentRegistry()
                .register(new RequirementsAgent())
                .register(new ArchitectAgent())
                .register(new RiskAgent())
                .register(new ReviewAgent())
                .register(new ImpactAnalysisAgent())
                .register(new TemplateAgent("implementer", false))
                .register(new TemplateAgent("tester", false))
                .register(new TemplateAgent("implementer.fallback", true))
                .register(new DocumentationAgent())
                .register(new ReleaseAgent());
    }
}

package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;

import java.util.List;
import java.util.Map;

/** Normalises the raw requirement into structured functional/security/performance/ambiguity artifacts. */
public final class RequirementsAgent implements Agent {
    @Override
    public String name() { return "requirements"; }

    @Override
    public AgentResult execute(TaskContext ctx) {
        String text = ctx.scenario().requirement;
        if (text == null || text.isBlank()) throw new IllegalStateException("scenario has no requirement text");
        Map<String, String> arts = RequirementsAnalyzer.analyze(text);
        List<ArtifactWrite> out = arts.entrySet().stream()
                .map(e -> new ArtifactWrite(e.getKey(), "json", e.getValue())).toList();
        int amb = ((List<?>) RequirementsAnalyzer.parse(arts.get("req.ambiguities")).get("items")).size();
        return AgentResult.of("extracted " + RequirementsAnalyzer.features(arts.get("req.functional")).size()
                + " features, " + amb + " ambiguities", out, List.of());
    }
}

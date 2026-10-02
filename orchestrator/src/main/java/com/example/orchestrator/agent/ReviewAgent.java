package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.Artifact;
import com.example.orchestrator.model.ArtifactWrite;

import java.util.List;

/**
 * Prepares the briefing a human reviewer approves at a gate: exactly which artifact versions are being
 * approved (id, version, hash prefix) and what remains open. The gate decision is recorded in the audit log.
 */
public final class ReviewAgent implements Agent {
    @Override
    public String name() { return "reviewer"; }

    @Override
    public AgentResult execute(TaskContext ctx) {
        StringBuilder sb = new StringBuilder("# Review briefing: " + ctx.node().id + "\n\nReason: "
                + (ctx.node().gateReason == null ? "approval checkpoint" : ctx.node().gateReason) + "\n\n"
                + "| Artifact | Version | SHA-256 |\n|---|---|---|\n");
        for (String id : ctx.node().consumes) {
            Artifact a = ctx.context().latest(id).orElseThrow();
            sb.append("| ").append(id).append(" | v").append(a.version()).append(" | ").append(a.sha256(), 0, 12).append(" |\n");
        }
        if (ctx.context().has("req.ambiguities")) {
            sb.append("\nOpen assumptions:\n");
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> items = (List<java.util.Map<String, Object>>)
                    RequirementsAnalyzer.parse(ctx.context().content("req.ambiguities")).get("items");
            for (var a : items)
                sb.append("- ").append(a.get("id")).append(" [").append(a.get("status")).append("] ").append(a.get("term"))
                        .append(": ").append("RESOLVED".equals(a.get("status")) ? a.get("answer") : a.get("assumption")).append('\n');
        }
        String out = ctx.node().produces.isEmpty() ? ctx.node().id + ".briefing" : ctx.node().produces.get(0);
        return AgentResult.of("briefing for " + ctx.node().consumes.size() + " artifacts",
                List.of(new ArtifactWrite(out, "markdown", sb.toString())), List.of());
    }
}

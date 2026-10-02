package com.example.orchestrator.engine;

import com.example.orchestrator.audit.AuditVerifier;
import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.graph.WorkflowGraph;
import com.example.orchestrator.model.Artifact;
import com.example.orchestrator.model.NodeSpec;
import com.example.orchestrator.model.NodeState;
import com.example.orchestrator.model.ScenarioSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Writes metrics.json, lineage.json and a human-readable report.md for a finished (or halted) run. */
final class RunReporter {
    private RunReporter() {}

    static void write(Path runDir, ScenarioSpec sc, WorkflowGraph graph, Map<String, NodeState> states,
                      Map<String, Object> metrics, ContextStore ctx, List<Map<String, Object>> lineage,
                      List<Map<String, Object>> decisions, AuditVerifier.Result audit, String status, String reason) throws IOException {
        ObjectMapper om = new ObjectMapper();
        om.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("metrics.json").toFile(), metrics);
        om.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("lineage.json").toFile(),
                Map.of("executions", lineage, "decisions", decisions));

        StringBuilder md = new StringBuilder("# Run report: ").append(sc.name).append("\n\n");
        md.append("**Status:** ").append(status).append(reason == null ? "" : " (" + reason + ")").append("\n\n");
        md.append("> ").append(sc.requirement.trim()).append("\n\n");
        md.append("**Audit chain:** ").append(audit.valid() ? "VALID" : "INVALID").append(" - ").append(audit.records())
                .append(" records, ").append(audit.detail()).append("\n\n");
        md.append("## Workflow graph\n\n```mermaid\n").append(graph.toMermaid()).append("```\n\n");
        md.append("Parallel layers: ").append(graph.layers()).append("  \nJoin (sync) nodes: ").append(graph.joins()).append("\n\n");
        md.append("## Nodes\n\n| Node | Agent | Status | Attempts | Fallback | Rollbacks | Runs |\n|---|---|---|---|---|---|---|\n");
        for (NodeSpec n : graph.nodes()) {
            NodeState s = states.get(n.id);
            md.append("| ").append(n.id).append(n.humanGate ? " (gate)" : "").append(" | ").append(n.agent).append(" | ").append(s.status)
                    .append(" | ").append(s.attempts).append(" | ").append(s.usedFallback).append(" | ").append(s.rollbacks)
                    .append(" | ").append(s.runs).append(" |\n");
        }
        md.append("\n## Reliability metrics\n\n| Metric | Value |\n|---|---|\n");
        metrics.forEach((k, v) -> { if (!(v instanceof Map)) md.append("| ").append(k).append(" | ").append(v).append(" |\n"); });
        md.append("\n## Decisions\n\n");
        for (Map<String, Object> d : decisions)
            md.append("- **").append(d.get("type")).append("** ").append(d.get("node") == null ? "" : "[" + d.get("node") + "] ")
                    .append("by ").append(d.get("actor")).append(": ").append(d.get("text")).append('\n');
        md.append("\n## Decision lineage (inputs to outputs)\n\n| Node | Run | Agent | Consumed | Produced |\n|---|---|---|---|---|\n");
        for (Map<String, Object> l : lineage)
            md.append("| ").append(l.get("node")).append(" | ").append(l.get("run")).append(" | ").append(l.get("agent"))
                    .append(" | ").append(l.get("consumed")).append(" | ").append(l.get("produced")).append(" |\n");
        md.append("\n## Artifact versions\n\n| Artifact | Version | Produced by | SHA-256 |\n|---|---|---|---|\n");
        for (String id : ctx.ids())
            for (Artifact a : ctx.history(id))
                md.append("| ").append(id).append(" | v").append(a.version()).append(" | ").append(a.producedBy()).append(" | ")
                        .append(a.sha256(), 0, 12).append(" |\n");
        Files.writeString(runDir.resolve("report.md"), md.toString(), StandardCharsets.UTF_8);
    }
}

package com.example.orchestrator.approval;

import java.util.Map;

/** Decisions pre-recorded in the scenario: node id (or "*") to "approve" / "deny". Default is deny. */
public final class ScriptedApprovalGate implements ApprovalGate {
    private final Map<String, String> script;

    public ScriptedApprovalGate(Map<String, String> script) { this.script = Map.copyOf(script); }

    @Override
    public Decision request(Request r) {
        String d = script.getOrDefault(r.nodeId(), script.getOrDefault("*", "deny"));
        boolean ok = d.equalsIgnoreCase("approve");
        return new Decision(ok, "scripted-reviewer", "scenario script: " + d);
    }
}

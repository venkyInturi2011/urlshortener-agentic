package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assesses release readiness from run facts and recommends GO / CONDITIONAL / NO-GO for the human gate. */
public final class ReleaseAgent implements Agent {
    @Override
    public String name() { return "release-manager"; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult execute(TaskContext ctx) {
        Map<String, Object> facts = ctx.runFacts();
        List<String> notDone = (List<String>) facts.get("nodesNotDone");
        notDone.remove(ctx.node().id);
        boolean testsRan = Boolean.TRUE.equals(facts.get("testsExecuted"));
        List<String> openAmb = (List<String>) facts.get("openAmbiguities");

        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("All upstream stages complete", notDone.isEmpty(), notDone.isEmpty() ? "yes" : "pending: " + notDone));
        checks.add(check("Tests executed against generated code", testsRan, testsRan ? "mvn test passed" : "build/test validators were skipped"));
        checks.add(check("No unresolved policy blocks", ((Number) facts.get("policyBlocks")).intValue() == 0, facts.get("policyBlocks") + " blocks"));
        checks.add(check("Ambiguities resolved or approved", openAmb.isEmpty(), openAmb.isEmpty() ? "none open" : "open: " + openAmb));
        checks.add(check("Audit chain intact so far", Boolean.TRUE.equals(facts.get("auditIntact")), String.valueOf(facts.get("auditDetail"))));

        String verdict = !notDone.isEmpty() || !Boolean.TRUE.equals(facts.get("auditIntact")) ? "NO-GO"
                : (!testsRan || !openAmb.isEmpty()) ? "CONDITIONAL" : "GO";
        StringBuilder md = new StringBuilder("# Release readiness: ").append(verdict).append("\n\n| Check | Result | Detail |\n|---|---|---|\n");
        for (Map<String, Object> c : checks)
            md.append("| ").append(c.get("check")).append(" | ").append((boolean) c.get("passed") ? "PASS" : "FAIL").append(" | ").append(c.get("detail")).append(" |\n");
        md.append("\nRetries: ").append(facts.get("retries")).append(", rollbacks: ").append(facts.get("rollbacks"))
                .append(", replans: ").append(facts.get("replans")).append("\n\nKnown limitations are listed in docs/RISKS.md. "
                        + "A human must approve this gate; the recommendation is advisory.\n");
        String json = RequirementsAnalyzer.json(RequirementsAnalyzer.map("verdict", verdict, "checks", checks));
        return AgentResult.of("verdict " + verdict,
                List.of(new ArtifactWrite("release.readiness", "json", json), new ArtifactWrite("release.report", "markdown", md.toString())),
                List.of(new FileChange("docs/release/readiness.md", ChangeKind.CREATE, md.toString())));
    }

    private static Map<String, Object> check(String name, boolean ok, Object detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("passed", ok);
        m.put("detail", detail);
        return m;
    }
}

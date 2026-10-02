package com.example.orchestrator.resilience;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.ScenarioSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Deterministic chaos: makes the first N agent executions of a node fail in a chosen way. */
public final class FaultInjector {
    public static final class InjectedFault extends RuntimeException {
        public InjectedFault(String m) { super(m); }
    }

    private final List<ScenarioSpec.FaultSpec> faults;
    private final Map<String, Integer> counts = new HashMap<>();

    public FaultInjector(List<ScenarioSpec.FaultSpec> faults) { this.faults = List.copyOf(faults); }

    public static FaultInjector none() { return new FaultInjector(List.of()); }

    /** Parse "node:times:mode[:both]". */
    public static ScenarioSpec.FaultSpec parse(String spec) {
        String[] p = spec.split(":");
        ScenarioSpec.FaultSpec f = new ScenarioSpec.FaultSpec();
        f.node = p[0];
        if (p.length > 1) f.times = Integer.parseInt(p[1]);
        if (p.length > 2) f.mode = p[2];
        if (p.length > 3) f.includeFallback = p[3].equals("both");
        return f;
    }

    private synchronized ScenarioSpec.FaultSpec active(String node, boolean fallback) {
        for (ScenarioSpec.FaultSpec f : faults) {
            if (!f.node.equals(node) || (fallback && !f.includeFallback)) continue;
            String key = node + (fallback ? "#fb" : "");
            int n = counts.getOrDefault(key, 0);
            if (n < f.times) {
                counts.put(key, n + 1);
                return f;
            }
        }
        return null;
    }

    /** Called before the agent runs: may throw. Returns the fault if output corruption is requested. */
    public ScenarioSpec.FaultSpec before(String node, boolean fallback) {
        ScenarioSpec.FaultSpec f = active(node, fallback);
        if (f != null && f.mode.equals("exception"))
            throw new InjectedFault("injected exception in " + node);
        return f;
    }

    public AgentResult corrupt(ScenarioSpec.FaultSpec f, AgentResult r) {
        if (f == null || f.mode.equals("exception")) return r;
        List<FileChange> changes = new ArrayList<>(r.changes());
        if (f.mode.equals("bad-output")) {
            for (int i = 0; i < changes.size(); i++) {
                FileChange c = changes.get(i);
                if (c.path().endsWith(".java")) {
                    changes.set(i, new FileChange(c.path(), c.kind(), c.content() + "\n// injected }}} {{{ broken\npublic class {"));
                    break;
                }
            }
        } else if (f.mode.equals("policy")) {
            changes.add(new FileChange("src/main/java/com/example/shortener/Injected.java", ChangeKind.CREATE,
                    "package com.example.shortener;\nclass Injected { String password = \"SuperSecret12345\"; }\n"));
        }
        return new AgentResult(r.artifacts(), changes, r.summary());
    }
}

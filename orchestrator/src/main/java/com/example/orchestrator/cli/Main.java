package com.example.orchestrator.cli;

import com.example.orchestrator.agent.AgentRegistry;
import com.example.orchestrator.approval.*;
import com.example.orchestrator.audit.AuditVerifier;
import com.example.orchestrator.engine.EngineConfig;
import com.example.orchestrator.engine.RunResult;
import com.example.orchestrator.engine.WorkflowEngine;
import com.example.orchestrator.model.NodeState;
import com.example.orchestrator.model.ScenarioSpec;
import com.example.orchestrator.policy.PolicyEngine;
import com.example.orchestrator.resilience.FaultInjector;
import com.example.orchestrator.validate.ValidatorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/** Command line entry point: run | resume | verify-audit | graph. */
public final class Main {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        System.exit(new Main().execute(args));
    }

    int execute(String[] args) throws Exception {
        if (args.length == 0) return usage();
        Map<String, String> opts = parse(Arrays.copyOfRange(args, 1, args.length));
        switch (args[0]) {
            case "run":
                return run(opts);
            case "resume":
                return resume(opts);
            case "verify-audit": {
                AuditVerifier.Result r = AuditVerifier.verify(Path.of(require(opts, "run-dir")).resolve("audit.jsonl"));
                System.out.println((r.valid() ? "VALID: " : "INVALID: ") + r.records() + " records - " + r.detail());
                return r.valid() ? 0 : 3;
            }
            case "graph": {
                ScenarioSpec sc = loadScenario(require(opts, "scenario"));
                System.out.println(new com.example.orchestrator.graph.WorkflowGraph(sc.nodes).toMermaid());
                return 0;
            }
            default:
                return usage();
        }
    }

    private int run(Map<String, String> o) throws Exception {
        String scenarioRef = require(o, "scenario");
        ScenarioSpec sc = loadScenario(scenarioRef);
        String runId = sc.name + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path runDir = Path.of(o.getOrDefault("run-dir", "workspace/runs/" + runId)).toAbsolutePath();
        Path target = Path.of(require(o, "target")).toAbsolutePath();
        if (o.containsKey("copy-from")) copyProject(Path.of(o.get("copy-from")).toAbsolutePath(), target);
        Files.createDirectories(runDir);
        Map<String, Object> runInfo = new LinkedHashMap<>(o);
        runInfo.put("runId", runId);
        runInfo.put("scenario", scenarioRef);
        runInfo.put("target", target.toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(runDir.resolve("run.json").toFile(), runInfo);
        return execute(sc, o, runId, runDir, target, false);
    }

    @SuppressWarnings("unchecked")
    private int resume(Map<String, String> o) throws Exception {
        Path runDir = Path.of(require(o, "run-dir")).toAbsolutePath();
        Map<String, Object> info = JSON.readValue(runDir.resolve("run.json").toFile(), Map.class);
        Map<String, String> saved = new LinkedHashMap<>();
        info.forEach((k, v) -> saved.put(k, String.valueOf(v)));
        saved.remove("inject-failure"); // faults are one-shot: a resumed run is the "fixed" run
        saved.putAll(o);
        ScenarioSpec sc = loadScenario(saved.get("scenario"));
        return execute(sc, saved, saved.get("runId"), runDir, Path.of(saved.get("target")), true);
    }

    private int execute(ScenarioSpec sc, Map<String, String> o, String runId, Path runDir, Path target, boolean resume) throws Exception {
        EngineConfig cfg = EngineConfig.defaults(runDir, target).withResume(resume)
                .withSkipBuild(o.containsKey("skip-build"));
        if (o.containsKey("max-retries")) cfg = cfg.withMaxRetries(Integer.parseInt(o.get("max-retries")));
        if (o.containsKey("parallelism")) cfg = cfg.withParallelism(Integer.parseInt(o.get("parallelism")));
        List<ScenarioSpec.FaultSpec> faults = new ArrayList<>(sc.faults);
        if (o.containsKey("inject-failure"))
            for (String f : o.get("inject-failure").split(",")) faults.add(FaultInjector.parse(f.trim()));
        ApprovalGate gate = switch (o.getOrDefault("approve", "scripted")) {
            case "auto" -> new AutoApproveGate();
            case "interactive" -> new ConsoleApprovalGate(System.in, System.out);
            default -> new ScriptedApprovalGate(sc.approvals);
        };
        System.out.println("Run " + runId + " | scenario " + sc.name + " | target " + target + " | approvals " + o.getOrDefault("approve", "scripted")
                + (cfg.skipBuild() ? " | BUILD VALIDATION SKIPPED" : ""));
        WorkflowEngine engine = new WorkflowEngine(sc, cfg, AgentRegistry.defaults(), ValidatorRegistry.defaults(),
                PolicyEngine.defaults(), gate, new FaultInjector(faults), runId);
        RunResult r = engine.run();
        for (var e : r.nodes().entrySet()) {
            NodeState s = e.getValue();
            System.out.printf("  %-24s %-9s attempts=%d%s%n", e.getKey(), s.status, s.attempts, s.usedFallback ? " (fallback)" : "");
        }
        System.out.println("Status: " + r.status() + (r.haltReason() == null ? "" : " - " + r.haltReason()));
        System.out.println("Metrics: " + r.metrics().entrySet().stream().filter(e -> !(e.getValue() instanceof Map))
                .map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse(""));
        System.out.println("Report: " + runDir.resolve("report.md"));
        return r.completed() ? 0 : 2;
    }

    static ScenarioSpec loadScenario(String ref) throws IOException {
        YAMLMapper ym = new YAMLMapper();
        Path p = Path.of(ref);
        if (Files.exists(p)) return ym.readValue(p.toFile(), ScenarioSpec.class);
        try (InputStream in = Main.class.getResourceAsStream("/scenarios/" + ref + ".yaml")) {
            if (in == null) throw new IOException("unknown scenario '" + ref + "' (not a file, not a built-in)");
            return ym.readValue(in, ScenarioSpec.class);
        }
    }

    private static void copyProject(Path from, Path to) throws IOException {
        if (Files.exists(to) && !isEmpty(to)) throw new IOException("--copy-from requires an empty or missing target: " + to);
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(from) && (n.equals("target") || n.equals("data") || n.equals(".git"))) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(to.resolve(from.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.copy(f, to.resolve(from.relativize(f)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.findAny().isEmpty(); }
    }

    private static Map<String, String> parse(String[] a) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < a.length; i++) {
            if (!a[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + a[i]);
            String k = a[i].substring(2);
            if (k.equals("skip-build")) { m.put(k, "true"); continue; }
            if (k.contains("=")) { m.put(k.substring(0, k.indexOf('=')), k.substring(k.indexOf('=') + 1)); continue; }
            if (i + 1 >= a.length) throw new IllegalArgumentException("missing value for --" + k);
            m.put(k, a[++i]);
        }
        return m;
    }

    private static String require(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) throw new IllegalArgumentException("missing --" + k);
        return v;
    }

    private static int usage() {
        System.out.println("""
                Usage:
                  run --scenario <greenfield|brownfield|ambiguous|file.yaml> --target <dir> [--copy-from <dir>]
                      [--run-dir <dir>] [--approve scripted|auto|interactive] [--skip-build]
                      [--inject-failure node:times:mode[:both]] [--max-retries N] [--parallelism N]
                  resume --run-dir <dir> [--approve ...]
                  verify-audit --run-dir <dir>
                  graph --scenario <name>""");
        return 1;
    }
}

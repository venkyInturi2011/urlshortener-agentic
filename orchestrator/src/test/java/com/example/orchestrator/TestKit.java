package com.example.orchestrator;

import com.example.orchestrator.agent.Agent;
import com.example.orchestrator.agent.AgentRegistry;
import com.example.orchestrator.agent.TaskContext;
import com.example.orchestrator.approval.ApprovalGate;
import com.example.orchestrator.approval.AutoApproveGate;
import com.example.orchestrator.engine.EngineConfig;
import com.example.orchestrator.engine.WorkflowEngine;
import com.example.orchestrator.model.*;
import com.example.orchestrator.policy.PolicyEngine;
import com.example.orchestrator.resilience.FaultInjector;
import com.example.orchestrator.validate.ValidatorRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Small builders so engine tests read as scenarios rather than plumbing. */
public final class TestKit {
    private TestKit() {}

    @FunctionalInterface
    public interface Body {
        AgentResult run(TaskContext ctx) throws Exception;
    }

    public static Agent agent(String name, Body body) {
        return new Agent() {
            @Override public String name() { return name; }
            @Override public AgentResult execute(TaskContext ctx) throws Exception { return body.run(ctx); }
        };
    }

    public static AgentResult ok(String artifactId, FileChange... changes) {
        return AgentResult.of("ok", List.of(new ArtifactWrite(artifactId, "text", "content of " + artifactId)), List.of(changes));
    }

    public static FileChange file(String path, String content) {
        return new FileChange(path, ChangeKind.CREATE, content);
    }

    public static NodeSpec node(String id, String agent, String... deps) {
        NodeSpec n = new NodeSpec();
        n.id = id;
        n.agent = agent;
        n.dependsOn = List.of(deps);
        n.produces = List.of("art." + id);
        return n;
    }

    public static ScenarioSpec scenario(NodeSpec... nodes) {
        ScenarioSpec s = new ScenarioSpec();
        s.name = "test";
        s.description = "test scenario";
        s.requirement = "test requirement";
        s.nodes = List.of(nodes);
        return s;
    }

    public static AgentRegistry registry(Agent... agents) {
        AgentRegistry r = new AgentRegistry();
        for (Agent a : agents) r.register(a);
        return r;
    }

    public static EngineConfig cfg(Path tmp) {
        return EngineConfig.defaults(tmp.resolve("run"), tmp.resolve("target")).withSkipBuild(true).withBackoffMs(1);
    }

    public static WorkflowEngine engine(ScenarioSpec sc, EngineConfig cfg, AgentRegistry agents) {
        return engine(sc, cfg, agents, new AutoApproveGate(), FaultInjector.none(), ValidatorRegistry.defaults());
    }

    public static WorkflowEngine engine(ScenarioSpec sc, EngineConfig cfg, AgentRegistry agents, ApprovalGate gate,
                                        FaultInjector faults, ValidatorRegistry validators) {
        return new WorkflowEngine(sc, cfg, agents, validators, PolicyEngine.defaults(), gate, faults, "test-run");
    }

    public static boolean auditHas(Path runDir, String type) throws IOException {
        return Files.readAllLines(runDir.resolve("audit.jsonl")).stream().anyMatch(l -> l.contains("\"type\":\"" + type + "\""));
    }

    public static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                Path dest = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(dest);
                else Files.copy(p, dest);
            }
        }
    }

    public static Map<String, Object> empty() { return Map.of(); }
}

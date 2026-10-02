package com.example.orchestrator.agent;

import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.model.NodeSpec;
import com.example.orchestrator.model.ScenarioSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Read-only view handed to an agent. */
public final class TaskContext {
    private final NodeSpec node;
    private final ScenarioSpec scenario;
    private final ContextStore context;
    private final Path targetDir;
    private final int attempt;
    private final boolean fallback;
    private final Supplier<Map<String, Object>> runFacts;

    public TaskContext(NodeSpec node, ScenarioSpec scenario, ContextStore context, Path targetDir, int attempt,
                       boolean fallback, Supplier<Map<String, Object>> runFacts) {
        this.node = node;
        this.scenario = scenario;
        this.context = context;
        this.targetDir = targetDir;
        this.attempt = attempt;
        this.fallback = fallback;
        this.runFacts = runFacts;
    }

    public NodeSpec node() { return node; }
    public ScenarioSpec scenario() { return scenario; }
    public ContextStore context() { return context; }
    public Path targetDir() { return targetDir; }
    public int attempt() { return attempt; }
    public boolean fallback() { return fallback; }
    public Map<String, Object> runFacts() { return runFacts.get(); }

    public String param(String key, String dflt) { return node.params.getOrDefault(key, dflt); }

    public Optional<String> readTarget(String relPath) {
        try {
            Path f = targetDir.resolve(relPath).normalize();
            if (!f.startsWith(targetDir.toAbsolutePath().normalize()) || !Files.exists(f)) return Optional.empty();
            return Optional.of(Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}

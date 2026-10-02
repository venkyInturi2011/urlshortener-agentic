package com.example.orchestrator.engine;

import java.nio.file.Path;

/** Execution limits and paths. Bounded retries, backoff, parallelism and global budgets are all explicit. */
public record EngineConfig(Path runDir, Path targetDir, boolean skipBuild, int defaultMaxRetries, long backoffMs,
                           int parallelism, long maxRuntimeMs, int maxAttemptsTotal, boolean resume) {
    public static EngineConfig defaults(Path runDir, Path targetDir) {
        return new EngineConfig(runDir, targetDir, false, 2, 100, 4, 45 * 60_000L, 200, false);
    }

    public EngineConfig withSkipBuild(boolean v) {
        return new EngineConfig(runDir, targetDir, v, defaultMaxRetries, backoffMs, parallelism, maxRuntimeMs, maxAttemptsTotal, resume);
    }

    public EngineConfig withBackoffMs(long v) {
        return new EngineConfig(runDir, targetDir, skipBuild, defaultMaxRetries, v, parallelism, maxRuntimeMs, maxAttemptsTotal, resume);
    }

    public EngineConfig withMaxRetries(int v) {
        return new EngineConfig(runDir, targetDir, skipBuild, v, backoffMs, parallelism, maxRuntimeMs, maxAttemptsTotal, resume);
    }

    public EngineConfig withParallelism(int v) {
        return new EngineConfig(runDir, targetDir, skipBuild, defaultMaxRetries, backoffMs, v, maxRuntimeMs, maxAttemptsTotal, resume);
    }

    public EngineConfig withResume(boolean v) {
        return new EngineConfig(runDir, targetDir, skipBuild, defaultMaxRetries, backoffMs, parallelism, maxRuntimeMs, maxAttemptsTotal, v);
    }
}

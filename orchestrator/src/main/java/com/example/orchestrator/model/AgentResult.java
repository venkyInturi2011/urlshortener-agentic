package com.example.orchestrator.model;

import java.util.List;

public record AgentResult(List<ArtifactWrite> artifacts, List<FileChange> changes, String summary) {
    public static AgentResult of(String summary, List<ArtifactWrite> artifacts, List<FileChange> changes) {
        return new AgentResult(artifacts, changes, summary);
    }
}

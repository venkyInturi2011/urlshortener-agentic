package com.example.orchestrator.model;

/** A proposed change to the target project. Agents only propose; the engine applies after governance. */
public record FileChange(String path, ChangeKind kind, String content) {
    public FileChange withKind(ChangeKind k) {
        return new FileChange(path, k, content);
    }
}

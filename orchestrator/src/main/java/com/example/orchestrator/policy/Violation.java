package com.example.orchestrator.policy;

public record Violation(String rule, Severity severity, String path, String message) {
    public enum Severity { WARN, BLOCK }
}

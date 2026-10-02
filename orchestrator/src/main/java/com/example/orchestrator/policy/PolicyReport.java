package com.example.orchestrator.policy;

import java.util.List;

public record PolicyReport(List<Violation> violations) {
    public boolean blocked() {
        return violations.stream().anyMatch(v -> v.severity() == Violation.Severity.BLOCK);
    }

    public List<Violation> warnings() {
        return violations.stream().filter(v -> v.severity() == Violation.Severity.WARN).toList();
    }
}

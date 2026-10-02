package com.example.orchestrator.policy;

import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PolicyEngine {
    private final List<PolicyRule> rules;

    public PolicyEngine(List<PolicyRule> rules) { this.rules = List.copyOf(rules); }

    public static PolicyEngine defaults() {
        return new PolicyEngine(List.of(new SecurityRule(), new ComplianceRule(), new ChangeControlRule()));
    }

    public PolicyReport evaluate(NodeSpec node, List<FileChange> changes, Path targetDir) {
        List<Violation> all = new ArrayList<>();
        for (PolicyRule r : rules) all.addAll(r.check(node, changes, targetDir));
        return new PolicyReport(all);
    }
}

package com.example.orchestrator.policy;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Compliance guardrails: no PII in logs, no copyleft licence text pulled into the codebase. */
public final class ComplianceRule implements PolicyRule {
    private static final Pattern PII_LOG = Pattern.compile(
            "(?i)\\blog(?:ger)?\\.\\w+\\([^;]*\\b(email|ssn|password|creditcard|card_?number)\\b");
    private static final Pattern COPYLEFT = Pattern.compile("GNU (?:Affero )?General Public License");

    @Override
    public String id() { return "compliance"; }

    @Override
    public List<Violation> check(NodeSpec node, List<FileChange> changes, Path targetDir) {
        List<Violation> out = new ArrayList<>();
        for (FileChange c : changes) {
            if (c.kind() == ChangeKind.DELETE || c.content() == null) continue;
            String p = c.path().replace('\\', '/');
            if (p.endsWith(".java") && PII_LOG.matcher(c.content()).find())
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "possible PII written to logs"));
            if (COPYLEFT.matcher(c.content()).find())
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "copyleft licence text requires legal review"));
        }
        return out;
    }
}

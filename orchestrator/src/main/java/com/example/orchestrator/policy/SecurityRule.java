package com.example.orchestrator.policy;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Security guardrails: hardcoded secrets, command execution, unsafe deserialization, SQL concatenation. */
public final class SecurityRule implements PolicyRule {
    private record Pattern1(String name, Pattern pattern) {}

    private static final List<Pattern1> PATTERNS = List.of(
            new Pattern1("hardcoded secret",
                    Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"']?[A-Za-z0-9/+_\\-]{8,}[\"']?")),
            new Pattern1("command execution",
                    Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder")),
            new Pattern1("unsafe deserialization", Pattern.compile("\\bObjectInputStream\\b")),
            new Pattern1("SQL built by string concatenation",
                    Pattern.compile("(?:query\\w*|update|execute)\\(\\s*\"[^\"]*\"\\s*\\+")));

    @Override
    public String id() { return "security"; }

    @Override
    public List<Violation> check(NodeSpec node, List<FileChange> changes, Path targetDir) {
        List<Violation> out = new ArrayList<>();
        for (FileChange c : changes) {
            if (c.kind() == ChangeKind.DELETE || c.content() == null) continue;
            String p = c.path().replace('\\', '/');
            boolean scanned = p.endsWith(".java") || p.endsWith(".yml") || p.endsWith(".yaml") || p.endsWith(".properties");
            if (!scanned) continue;
            boolean test = p.contains("src/test/");
            for (Pattern1 pat : PATTERNS) {
                if (pat.pattern().matcher(c.content()).find()) {
                    out.add(new Violation(id(), test ? Violation.Severity.WARN : Violation.Severity.BLOCK, p,
                            pat.name() + " detected"));
                }
            }
        }
        return out;
    }
}

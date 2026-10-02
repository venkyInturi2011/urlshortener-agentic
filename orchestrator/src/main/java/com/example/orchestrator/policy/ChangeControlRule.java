package com.example.orchestrator.policy;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Change-control guardrails: path confinement, protected paths, size limits, dependency allow-list. */
public final class ChangeControlRule implements PolicyRule {
    static final int MAX_FILES_PER_NODE = 40;
    static final int MAX_FILE_BYTES = 200_000;
    private static final Set<String> EXTENSIONS = Set.of(".java", ".xml", ".yml", ".yaml", ".sql", ".md", ".json",
            ".properties", ".txt", ".gitignore");
    private static final Set<String> ALLOWED_GROUPS = Set.of("org.springframework.boot", "org.springframework",
            "com.h2database", "org.junit.jupiter", "org.assertj", "org.mockito");
    private static final Pattern DEPENDENCY_GROUP = Pattern.compile("<dependency>\\s*<groupId>([^<]+)</groupId>");

    @Override
    public String id() { return "change-control"; }

    @Override
    public List<Violation> check(NodeSpec node, List<FileChange> changes, Path targetDir) {
        List<Violation> out = new ArrayList<>();
        if (changes.size() > MAX_FILES_PER_NODE)
            out.add(new Violation(id(), Violation.Severity.BLOCK, "*", "change set exceeds " + MAX_FILES_PER_NODE + " files"));
        Path root = targetDir.toAbsolutePath().normalize();
        for (FileChange c : changes) {
            String p = c.path().replace('\\', '/');
            Path resolved = root.resolve(p).normalize();
            if (p.startsWith("/") || p.contains(":") || !resolved.startsWith(root)) {
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "path escapes target directory"));
                continue;
            }
            if (p.startsWith(".git/") || p.startsWith(".github/"))
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "protected path"));
            if (c.kind() == ChangeKind.DELETE) continue;
            String lower = p.toLowerCase();
            if (EXTENSIONS.stream().noneMatch(lower::endsWith))
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "file type not allowed"));
            if (c.content() != null && c.content().length() > MAX_FILE_BYTES)
                out.add(new Violation(id(), Violation.Severity.BLOCK, p, "file exceeds size limit"));
            if (p.equals("pom.xml") && c.content() != null) {
                Matcher m = DEPENDENCY_GROUP.matcher(c.content());
                while (m.find())
                    if (!ALLOWED_GROUPS.contains(m.group(1).trim()))
                        out.add(new Violation(id(), Violation.Severity.BLOCK, p, "unapproved dependency group " + m.group(1).trim()));
            }
        }
        return out;
    }
}

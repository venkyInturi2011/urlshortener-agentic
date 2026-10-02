package com.example.orchestrator.agent;

import com.example.orchestrator.context.Hashing;
import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation/test-writing agent backed by a library of vetted code templates ("change sets").
 * Parameters come from the node and from requirement artifacts, so a changed requirement yields different code.
 * The fallback variant ignores requirement-derived parameters and renders the vetted baseline.
 */
public final class TemplateAgent implements Agent {
    private static final Pattern PLACEHOLDER = Pattern.compile("@@([A-Z_]+)@@");
    private final String name;
    private final boolean baselineOnly;

    public TemplateAgent(String name, boolean baselineOnly) {
        this.name = name;
        this.baselineOnly = baselineOnly;
    }

    @Override
    public String name() { return name; }

    @Override
    public AgentResult execute(TaskContext ctx) throws IOException {
        String changeset = ctx.node().params.get("changeset");
        if (changeset == null) throw new IllegalStateException("node " + ctx.node().id + " has no 'changeset' param");
        Map<String, String> vars = variables(ctx);
        List<FileChange> changes = new ArrayList<>();
        for (String rel : readResource("/templates/" + changeset + "/index.txt").lines()
                .map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList()) {
            String body = render(readResource("/templates/" + changeset + "/" + rel), vars, rel);
            changes.add(new FileChange(rel.endsWith(".tpl") ? rel.substring(0, rel.length() - 4) : rel, ChangeKind.CREATE, body));
        }
        StringBuilder manifest = new StringBuilder("{\"changeset\":\"" + changeset + "\",\"files\":[");
        manifest.append(changes.stream().map(c -> "{\"path\":\"" + c.path() + "\",\"sha256\":\"" + Hashing.sha256(c.content()).substring(0, 12) + "\"}")
                .collect(Collectors.joining(",")));
        manifest.append("]}");
        String artifactId = ctx.node().produces.isEmpty() ? "code." + ctx.node().id : ctx.node().produces.get(0);
        return AgentResult.of(name + " rendered " + changes.size() + " files from " + changeset,
                List.of(new ArtifactWrite(artifactId, "json", manifest.toString())), changes);
    }

    private Map<String, String> variables(TaskContext ctx) {
        Map<String, String> v = new HashMap<>();
        List<String> schemes = List.of("http", "https");
        int ttl = 60;
        if (!baselineOnly) {
            if (ctx.context().has("req.security")) {
                @SuppressWarnings("unchecked")
                List<String> s = (List<String>) RequirementsAnalyzer.parse(ctx.context().content("req.security")).get("allowedSchemes");
                schemes = s;
            }
            if (ctx.context().has("req.performance"))
                ttl = ((Number) RequirementsAnalyzer.parse(ctx.context().content("req.performance")).get("cacheTtlSeconds")).intValue();
        }
        v.put("ALLOWED_SCHEMES", schemes.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ")));
        v.put("HTTP_CHECK", schemes.contains("http")
                ? "assertThat(validator.validateUrl(\"http://example.com/a\")).isEqualTo(\"http://example.com/a\");"
                : "assertThatThrownBy(() -> validator.validateUrl(\"http://example.com/a\")).isInstanceOf(ShortenerException.class);");
        v.put("DENYLIST", "malware.example,phishing.example");
        v.put("CACHE_TTL", String.valueOf(ttl));
        v.put("SERVICE_VERSION", ctx.scenario().version);
        return v;
    }

    static String render(String template, Map<String, String> vars, String file) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = vars.get(m.group(1));
            if (val == null) throw new IllegalStateException("unresolved placeholder @@" + m.group(1) + "@@ in " + file);
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = TemplateAgent.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("template resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

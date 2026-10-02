package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Brownfield codebase reasoning: scans the real target sources, maps requested features to seed symbols,
 * and finds directly impacted files plus their callers/dependants (word-boundary reference scan).
 */
public final class ImpactAnalysisAgent implements Agent {
    private static final Map<String, List<String>> SEEDS = Map.of(
            "EXPIRY", List.of("ShortUrl", "CreateUrlRequest", "UrlResponse", "UrlService", "JdbcUrlRepository", "schema"),
            "DAILY_ANALYTICS", List.of("StatsResponse", "ClickRepository", "JdbcClickRepository", "AnalyticsService"),
            "BUGFIX_ALIAS_CASE", List.of("UrlValidator", "UrlService", "UrlRepository", "JdbcUrlRepository"));
    private static final Set<String> CONTRACT = Set.of("CreateUrlRequest", "UrlResponse", "StatsResponse");

    @Override
    public String name() { return "impact-analyst"; }

    @Override
    public AgentResult execute(TaskContext ctx) throws IOException {
        List<String> features = RequirementsAnalyzer.features(ctx.context().content("req.functional"));
        Path root = ctx.targetDir();
        Map<String, String> sources = new TreeMap<>(); // rel path -> content
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("src/") && (rel.endsWith(".java") || rel.endsWith(".sql") || rel.endsWith(".yml")))
                    sources.put(rel, Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        if (sources.isEmpty()) throw new IllegalStateException("no sources found in target; brownfield needs an existing codebase");

        Set<String> seedSymbols = new LinkedHashSet<>();
        for (String f : features) seedSymbols.addAll(SEEDS.getOrDefault(f, List.of()));

        Map<String, String> direct = new LinkedHashMap<>(); // path -> reason
        for (String rel : sources.keySet()) {
            String base = rel.substring(rel.lastIndexOf('/') + 1).replaceAll("\\..*$", "");
            if (seedSymbols.contains(base)) direct.put(rel, "seed symbol " + base);
        }
        Set<String> directTypes = new LinkedHashSet<>();
        direct.keySet().forEach(r -> directTypes.add(r.substring(r.lastIndexOf('/') + 1).replaceAll("\\..*$", "")));

        Map<String, String> dependants = new LinkedHashMap<>();
        for (var e : sources.entrySet()) {
            if (direct.containsKey(e.getKey()) || !e.getKey().endsWith(".java")) continue;
            for (String t : directTypes) {
                if (Pattern.compile("\\b" + t + "\\b").matcher(e.getValue()).find()) {
                    dependants.merge(e.getKey(), "references " + t, (a, b) -> a + ", " + t);
                }
            }
        }
        boolean contract = directTypes.stream().anyMatch(CONTRACT::contains);
        boolean schema = direct.keySet().stream().anyMatch(k -> k.endsWith(".sql"));
        String risk = contract || schema ? "HIGH (API contract and/or schema change; approval required)" : "MEDIUM";

        StringBuilder md = new StringBuilder("# Impact analysis\n\nFeatures: " + features + "\n\n");
        md.append("Scanned ").append(sources.size()).append(" source files.\n\n## Directly impacted\n\n| File | Reason |\n|---|---|\n");
        direct.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n## Dependants (callers / tests to re-verify)\n\n| File | Reason |\n|---|---|\n");
        dependants.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n## Data flow\n\nPOST /api/v1/urls -> UrlController -> UrlService.create -> UrlValidator -> UrlRepository -> short_url table; ")
                .append("GET /{code} -> RedirectController -> UrlService.resolve -> AnalyticsService -> click table.\n\n")
                .append("API contract change: **").append(contract).append("**; schema change: **").append(schema)
                .append("**; overall risk: **").append(risk).append("**.\n");

        String json = RequirementsAnalyzer.json(RequirementsAnalyzer.map("direct", direct.keySet(), "dependants", dependants.keySet(),
                "apiContractChange", contract, "schemaChange", schema, "risk", risk));
        return AgentResult.of(direct.size() + " files directly impacted, " + dependants.size() + " dependants",
                List.of(new ArtifactWrite("impact.report", "markdown", md.toString()), new ArtifactWrite("impact.summary", "json", json)),
                List.of(new FileChange("docs/design/impact-analysis.md", ChangeKind.CREATE, md.toString())));
    }
}

package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Generates the service README (from the OpenAPI + requirements) and appends a CHANGELOG entry. */
public final class DocumentationAgent implements Agent {
    @Override
    public String name() { return "documenter"; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult execute(TaskContext ctx) throws Exception {
        JsonNode api = new YAMLMapper().readTree(ctx.context().content("design.openapi"));
        Map<String, Object> func = RequirementsAnalyzer.parse(ctx.context().content("req.functional"));
        StringBuilder readme = new StringBuilder("# URL Shortener Service\n\nGenerated and maintained by the agentic SDLC orchestrator.\n\n## Endpoints\n\n");
        for (Iterator<Map.Entry<String, JsonNode>> it = api.path("paths").fields(); it.hasNext(); ) {
            var e = it.next();
            for (Iterator<Map.Entry<String, JsonNode>> m = e.getValue().fields(); m.hasNext(); ) {
                var op = m.next();
                readme.append("- `").append(op.getKey().toUpperCase()).append(' ').append(e.getKey()).append("` - ")
                        .append(op.getValue().path("summary").asText()).append('\n');
            }
        }
        readme.append("\n## Requirements covered\n\n");
        for (Map<String, Object> it : (List<Map<String, Object>>) func.get("items"))
            readme.append("- **").append(it.get("id")).append("** ").append(it.get("title")).append(": ").append(it.get("acceptance")).append('\n');
        readme.append("\n## Run\n\n```bash\nmvn spring-boot:run\n# create\ncurl -s -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' -d '{\"longUrl\":\"https://example.com\"}'\n```\n\n"
                + "## Configuration\n\n| Property | Default | Meaning |\n|---|---|---|\n"
                + "| `shortener.base-url` | http://localhost:8080 | Prefix used in returned short URLs |\n"
                + "| `shortener.admin-key` | (unset) | Required `X-API-Key` for DELETE; delete disabled when unset |\n"
                + "| `shortener.ratelimit.capacity` | 20 | Token bucket size per client address |\n"
                + "| `shortener.ratelimit.refill-per-second` | 1 | Refill rate |\n"
                + "\nSee `docs/design/` for architecture, OpenAPI, risk register and (brownfield) impact analysis.\n");

        String existing = ctx.readTarget("CHANGELOG.md").orElse("# Changelog\n");
        String entry = "\n## " + ctx.scenario().version + " - " + LocalDate.now() + "\n\n"
                + "Scenario `" + ctx.scenario().name + "`: " + ctx.scenario().description + "\n\n"
                + features(func) + "\n";
        String changelog = existing.replace("# Changelog\n", "# Changelog\n" + entry);
        if (existing.equals(changelog)) changelog = existing + entry;

        List<FileChange> changes = new ArrayList<>();
        changes.add(new FileChange("README.md", ChangeKind.CREATE, readme.toString()));
        changes.add(new FileChange("CHANGELOG.md", ChangeKind.CREATE, changelog));
        return AgentResult.of("README and CHANGELOG updated",
                List.of(new ArtifactWrite("docs.readme", "markdown", readme.toString())), changes);
    }

    @SuppressWarnings("unchecked")
    private static String features(Map<String, Object> func) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> it : (List<Map<String, Object>>) func.get("items")) sb.append("- ").append(it.get("title")).append('\n');
        return sb.toString();
    }
}

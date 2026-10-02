package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.util.*;

/** Produces the architecture document (components, flow, ADRs) and the OpenAPI contract from the requirements. */
public final class ArchitectAgent implements Agent {
    @Override
    public String name() { return "architect"; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult execute(TaskContext ctx) throws Exception {
        List<String> features = RequirementsAnalyzer.features(ctx.context().content("req.functional"));
        Map<String, Object> sec = RequirementsAnalyzer.parse(ctx.context().content("req.security"));
        Map<String, Object> perf = RequirementsAnalyzer.parse(ctx.context().content("req.performance"));
        boolean expiry = features.contains("EXPIRY"), daily = features.contains("DAILY_ANALYTICS");

        String openapi = new YAMLMapper().writeValueAsString(buildOpenApi(features, expiry, daily));
        StringBuilder md = new StringBuilder("# Architecture\n\n## Components\n\n```mermaid\nflowchart LR\n"
                + "  Client --> RateLimitFilter --> UrlController\n  Client --> RedirectController\n"
                + "  UrlController --> UrlService\n  RedirectController --> UrlService\n"
                + "  UrlService --> UrlValidator\n  UrlService --> CodeGenerator\n"
                + "  UrlService --> UrlRepository\n  RedirectController --> AnalyticsService --> ClickRepository\n"
                + "  UrlRepository --> H2[(H2 / JDBC)]\n  ClickRepository --> H2\n```\n\n"
                + "Layering: api -> service -> repository interface -> JDBC. Storage is replaceable behind `UrlRepository`.\n\n"
                + "## Key decisions (ADRs)\n\n"
                + "1. **302 not 301** - permanent redirects are cached by browsers, which would hide clicks and make delete/expiry ineffective.\n"
                + "2. **Soft delete** - preserves analytics history and gives a deterministic 410 for removed links.\n"
                + "3. **Random base62 codes with bounded collision retry** - unguessable, no coordination needed; allocation fails closed (503) after 5 attempts.\n"
                + "4. **In-memory token bucket per client address** - simple and fast; not shared across instances (see risks).\n"
                + "5. **SSRF guard at URL level** - scheme allow-list, credentials rejected, private/loopback/link-local/numeric hosts rejected; no DNS resolution at creation time.\n");
        if (expiry) md.append("6. **Expiry evaluated at read time** - no background job; `expires_at` is a nullable additive column.\n");
        if ((boolean) sec.get("hostDenylist")) md.append("7. **Host denylist** - configurable via `shortener.blocked-domains`; complements, not replaces, SSRF checks.\n");
        if ((boolean) perf.get("cacheEnabled")) md.append("8. **Redirect cache** - in-process, TTL ").append(perf.get("cacheTtlSeconds"))
                .append(" s, evicted on delete; staleness across instances is bounded by the TTL.\n");
        md.append("\n## Allowed schemes\n\n").append(sec.get("allowedSchemes")).append("\n");

        return AgentResult.of("architecture + OpenAPI for " + features.size() + " features",
                List.of(new ArtifactWrite("design.architecture", "markdown", md.toString()),
                        new ArtifactWrite("design.openapi", "yaml", openapi)),
                List.of(new FileChange("docs/design/architecture.md", ChangeKind.CREATE, md.toString()),
                        new FileChange("docs/design/openapi.yaml", ChangeKind.CREATE, openapi)));
    }

    private static Map<String, Object> buildOpenApi(List<String> features, boolean expiry, boolean daily) {
        Map<String, Object> paths = new LinkedHashMap<>();
        Map<String, Object> createProps = new LinkedHashMap<>();
        createProps.put("longUrl", Map.of("type", "string", "maxLength", 2048));
        if (features.contains("CUSTOM_ALIAS")) createProps.put("customAlias", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{3,32}$"));
        if (expiry) createProps.put("expiresAt", Map.of("type", "string", "format", "date-time"));
        Map<String, Object> post = new LinkedHashMap<>();
        post.put("summary", "Create a short URL");
        post.put("requestBody", Map.of("content", Map.of("application/json", Map.of("schema",
                Map.of("type", "object", "required", List.of("longUrl"), "properties", createProps)))));
        post.put("responses", responses("201", "Created", "200", "Existing short URL returned", "400", "Invalid URL or alias",
                "409", "Alias already taken", "429", "Rate limit exceeded"));
        paths.put("/api/v1/urls", Map.of("post", post));
        paths.put("/api/v1/urls/{code}", Map.of(
                "get", Map.of("summary", "Get link metadata", "responses", responses("200", "OK", "404", "Unknown", "410", "Deleted or expired")),
                "delete", Map.of("summary", "Soft-delete (requires X-API-Key)", "responses", responses("204", "Deleted", "403", "Forbidden", "404", "Unknown"))));
        Map<String, Object> statsProps = new LinkedHashMap<>();
        statsProps.put("totalClicks", Map.of("type", "integer"));
        statsProps.put("lastAccessedAt", Map.of("type", "string", "format", "date-time", "nullable", true));
        if (daily) statsProps.put("clicksByDay", Map.of("type", "object", "additionalProperties", Map.of("type", "integer")));
        paths.put("/api/v1/urls/{code}/stats", Map.of("get", Map.of("summary", "Click statistics",
                "responses", Map.of("200", Map.of("description", "OK", "content", Map.of("application/json",
                        Map.of("schema", Map.of("type", "object", "properties", statsProps)))), "404", Map.of("description", "Unknown")))));
        paths.put("/{code}", Map.of("get", Map.of("summary", "Redirect", "responses",
                responses("302", "Redirect", "404", "Unknown", "410", "Deleted or expired"))));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", Map.of("title", "URL Shortener", "version", "1.0.0"));
        doc.put("paths", paths);
        return doc;
    }

    private static Map<String, Object> responses(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], Map.of("description", kv[i + 1]));
        return m;
    }
}

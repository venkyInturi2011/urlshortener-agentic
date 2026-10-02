package com.example.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Rule-based requirement normalisation (a deterministic stand-in for an LLM analyst): extracts features,
 * flags vague quality terms as ambiguities, records explicit assumptions, and applies stakeholder answers.
 * Output is four JSON artifacts so downstream nodes depend only on the slice they actually need.
 */
public final class RequirementsAnalyzer {
    public static final ObjectMapper OM = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private record Feature(String key, Pattern pattern, String title, String acceptance) {}

    private static final List<Feature> FEATURES = List.of(
            new Feature("CREATE_SHORT_URL", Pattern.compile("shorten|short ?url|short ?link|create"),
                    "Create short URLs", "POST /api/v1/urls returns 201 with a unique code; same long URL returns the existing code (200)"),
            new Feature("REDIRECT", Pattern.compile("redirect"),
                    "Redirect", "GET /{code} returns 302 with Location; 404 if unknown; 410 if deleted or expired"),
            new Feature("CUSTOM_ALIAS", Pattern.compile("alias"),
                    "Custom aliases", "Alias 3-32 chars [A-Za-z0-9_-], not reserved, unique; 409 when taken"),
            new Feature("ANALYTICS", Pattern.compile("analytics|clicks?\\b|statistics|stats"),
                    "Click analytics", "GET /api/v1/urls/{code}/stats returns total clicks and last access time"),
            new Feature("DELETE", Pattern.compile("delete|remov"),
                    "Soft delete", "DELETE /api/v1/urls/{code} requires admin key, marks link deleted (redirect then 410)"),
            new Feature("RATE_LIMIT", Pattern.compile("rate.?limit|reliab"),
                    "Rate limiting", "Per-client token bucket on write APIs; 429 with Retry-After when exhausted"),
            new Feature("URL_VALIDATION", Pattern.compile("validat|safety|ssrf|safe"),
                    "URL safety validation", "Only http/https; private, loopback and link-local hosts rejected (SSRF)"),
            new Feature("EXPIRY", Pattern.compile("expir|\\bttl\\b"),
                    "Link expiry", "Optional expiresAt (future, max 5 years); redirect returns 410 after expiry"),
            new Feature("DAILY_ANALYTICS", Pattern.compile("per.?day|daily|by day"),
                    "Per-day click analytics", "Stats response includes clicksByDay (date to count)"),
            new Feature("BUGFIX_ALIAS_CASE", Pattern.compile("(bug|fix).*(case|reserved)|(case|reserved).*(bug|fix)"),
                    "Alias case-sensitivity fix", "Reserved-word check is case-insensitive; aliases differing only by case are rejected"));

    private record Vague(String id, Pattern pattern, String term, String question, String assumption) {}

    private static final List<Vague> VAGUE = List.of(
            new Vague("safer", Pattern.compile("\\bsafe(r|ty)?\\b|\\bsecur(e|er|ity)\\b"), "safer",
                    "What threat model does 'safer' cover: malicious destinations, abuse/spam of the API, SSRF, or data protection? Any scheme restrictions (HTTPS only)?",
                    "Block known-bad destination domains (denylist) and keep http/https; rate limiting stays on"),
            new Vague("faster", Pattern.compile("\\bfast(er)?\\b|\\bquick(er)?\\b|\\bperformance\\b|\\bspeed\\b"), "faster",
                    "What is the target (p95 redirect latency, requests per second) and the acceptable staleness for cached links?",
                    "Add an in-memory redirect cache (TTL 60 s) targeting p95 < 50 ms"));

    private RequirementsAnalyzer() {}

    public static Map<String, String> analyze(String requirement) {
        String t = requirement.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> features = new ArrayList<>();
        for (Feature f : FEATURES) {
            if (f.pattern().matcher(t).find()) {
                features.add(f.key());
                items.add(map("id", "FR-" + (items.size() + 1), "feature", f.key(), "title", f.title(), "acceptance", f.acceptance()));
            }
        }
        List<Map<String, Object>> amb = new ArrayList<>();
        for (Vague v : VAGUE) {
            if (v.pattern().matcher(t).find()) {
                amb.add(map("id", "AMB-" + (amb.size() + 1), "key", v.id(), "term", v.term(), "question", v.question(),
                        "assumption", v.assumption(), "status", "OPEN", "answer", null));
            }
        }
        boolean safer = amb.stream().anyMatch(a -> "safer".equals(a.get("key")));
        boolean faster = amb.stream().anyMatch(a -> "faster".equals(a.get("key")));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("req.functional", json(map("requirement", requirement, "features", features, "items", items)));
        out.put("req.security", json(map("allowedSchemes", List.of("http", "https"), "hostDenylist", safer,
                "rateLimit", true, "basis", safer ? "assumption AMB (safer)" : "explicit requirement")));
        out.put("req.performance", json(map("cacheEnabled", faster, "cacheTtlSeconds", 60,
                "p95TargetMs", faster ? 50 : null, "basis", faster ? "assumption AMB (faster)" : "none specified")));
        out.put("req.ambiguities", json(map("items", amb)));
        return out;
    }

    /** Apply stakeholder answers (ambiguity id to free text). Only slices that change get a new hash. */
    @SuppressWarnings("unchecked")
    public static Map<String, String> refine(Map<String, String> current, Map<String, String> answers) {
        try {
            Map<String, Object> amb = OM.readValue(current.get("req.ambiguities"), Map.class);
            Map<String, Object> sec = OM.readValue(current.get("req.security"), Map.class);
            Map<String, Object> perf = OM.readValue(current.get("req.performance"), Map.class);
            for (Map<String, Object> a : (List<Map<String, Object>>) amb.get("items")) {
                String ans = answers.get((String) a.get("id"));
                if (ans == null) continue;
                a.put("status", "RESOLVED");
                a.put("answer", ans);
                String l = ans.toLowerCase(Locale.ROOT);
                if ("safer".equals(a.get("key"))) {
                    if (l.contains("https")) sec.put("allowedSchemes", List.of("https"));
                    sec.put("hostDenylist", l.contains("denylist") || l.contains("blocklist") || (boolean) sec.get("hostDenylist"));
                    sec.put("basis", "stakeholder answer " + a.get("id"));
                } else if ("faster".equals(a.get("key"))) {
                    var m = Pattern.compile("(\\d+)\\s*(s|sec|seconds)\\b").matcher(l);
                    if (m.find()) perf.put("cacheTtlSeconds", Integer.parseInt(m.group(1)));
                    var p = Pattern.compile("p95[^\\d]*(\\d+)").matcher(l);
                    if (p.find()) perf.put("p95TargetMs", Integer.parseInt(p.group(1)));
                    perf.put("basis", "stakeholder answer " + a.get("id"));
                }
            }
            Map<String, String> out = new LinkedHashMap<>();
            out.put("req.security", json(sec));
            out.put("req.performance", json(perf));
            out.put("req.ambiguities", json(amb));
            return out;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> parse(String json) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> m = OM.readValue(json, LinkedHashMap.class);
            return m;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String json(Object o) {
        try {
            return OM.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static List<String> features(String functionalJson) {
        return (List<String>) parse(functionalJson).get("features");
    }
}

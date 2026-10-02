package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ArtifactWrite;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a risk register: per-feature failure scenarios, mitigations and the validation that covers them. */
public final class RiskAgent implements Agent {
    private record Risk(String id, String scenario, String mitigation, String validation) {}

    private static final Map<String, Risk> KB = new LinkedHashMap<>();

    static {
        KB.put("CREATE_SHORT_URL", new Risk("R-CODE", "Code collision or exhaustion under load",
                "Random 62^7 space, bounded retry (5), fail closed with 503", "UrlServiceTest collision/exhaustion cases"));
        KB.put("URL_VALIDATION", new Risk("R-SSRF", "Short link points at internal service (SSRF) or uses numeric-IP obfuscation",
                "Scheme allow-list; reject credentials, loopback/private/link-local, decimal/hex/short IPs", "UrlValidatorTest parameterised cases"));
        KB.put("REDIRECT", new Risk("R-OPENREDIR", "Short domain abused for phishing (open redirect by design)",
                "Rate limit creation; optional host denylist; soft delete for takedown", "Integration tests for 410 after delete"));
        KB.put("RATE_LIMIT", new Risk("R-RATE", "Per-instance limiter is bypassed by scaling out; X-Forwarded-For not trusted",
                "Documented limitation; move to shared store (Redis) for multi-instance", "RateLimitIntegrationTest (429)"));
        KB.put("ANALYTICS", new Risk("R-CLICK", "Synchronous click write adds latency and grows unbounded",
                "Failure to record never blocks redirect; retention/batching listed as follow-up", "Integration test: stats after redirect"));
        KB.put("DELETE", new Risk("R-AUTHZ", "Unauthenticated delete would allow link takedown by anyone",
                "Admin API key, constant-time compare, disabled when key unset", "Integration: 403 without key"));
        KB.put("EXPIRY", new Risk("R-MIGRATE", "Schema change on a live table",
                "Additive nullable column, idempotent migration, backward compatible API", "Regression suite + migration re-run"));
        KB.put("BUGFIX_ALIAS_CASE", new Risk("R-ALIAS", "Reserved words bypassed with different case; look-alike aliases",
                "Case-insensitive reserved check and uniqueness for aliases", "Dedicated regression tests"));
    }

    @Override
    public String name() { return "risk"; }

    @Override
    @SuppressWarnings("unchecked")
    public AgentResult execute(TaskContext ctx) {
        List<String> features = RequirementsAnalyzer.features(ctx.context().content("req.functional"));
        StringBuilder md = new StringBuilder("# Risk register\n\n| ID | Failure scenario | Mitigation | Validation |\n|---|---|---|---|\n");
        for (String f : features) {
            Risk r = KB.get(f);
            if (r != null) md.append("| ").append(r.id()).append(" | ").append(r.scenario()).append(" | ")
                    .append(r.mitigation()).append(" | ").append(r.validation()).append(" |\n");
        }
        List<Map<String, Object>> amb = (List<Map<String, Object>>) RequirementsAnalyzer
                .parse(ctx.context().content("req.ambiguities")).get("items");
        for (Map<String, Object> a : amb) {
            md.append("| R-").append(a.get("id")).append(" | Unresolved ambiguity '").append(a.get("term"))
                    .append("' (status ").append(a.get("status")).append(") | Proceed on assumption: ")
                    .append(a.get("assumption")).append(" | Human approval of assumptions at design review |\n");
        }
        String s = md.toString();
        return AgentResult.of("risk register with " + (s.split("\n").length - 4) + " entries",
                List.of(new ArtifactWrite("design.risks", "markdown", s)),
                List.of(new FileChange("docs/design/risk-register.md", ChangeKind.CREATE, s)));
    }
}

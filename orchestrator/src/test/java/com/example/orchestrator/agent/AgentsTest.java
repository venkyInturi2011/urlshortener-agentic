package com.example.orchestrator.agent;

import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;
import com.example.orchestrator.model.ScenarioSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentsTest {

    @TempDir Path tmp;

    private TaskContext ctx(NodeSpec node, ScenarioSpec sc, ContextStore store) {
        return new TaskContext(node, sc, store, tmp, 1, false, () -> Map.of());
    }

    private ContextStore withRequirements(String text) {
        ContextStore store = new ContextStore();
        RequirementsAnalyzer.analyze(text).forEach((id, json) -> store.put(id, "json", json, "req", "requirements", Map.of()));
        return store;
    }

    private static NodeSpec node(String id) {
        NodeSpec n = new NodeSpec();
        n.id = id;
        n.agent = id;
        return n;
    }

    private static ScenarioSpec scenario(String requirement) {
        ScenarioSpec s = new ScenarioSpec();
        s.name = "t";
        s.description = "d";
        s.requirement = requirement;
        return s;
    }

    // ---- requirements

    @Test
    void extractsFeaturesFromTheGreenfieldRequirement() {
        Map<String, String> arts = RequirementsAnalyzer.analyze(
                "Build a URL shortener with custom aliases, click analytics, delete, rate limiting and URL safety validation (SSRF)");
        assertThat(RequirementsAnalyzer.features(arts.get("req.functional")))
                .contains("CREATE_SHORT_URL", "CUSTOM_ALIAS", "ANALYTICS", "DELETE", "RATE_LIMIT", "URL_VALIDATION");
        assertThat(RequirementsAnalyzer.parse(arts.get("req.security")).get("hostDenylist")).isEqualTo(false);
    }

    @Test
    void doesNotFlagATermWhoseMeaningIsAlreadyStatedExplicitly() {
        Map<String, String> arts = RequirementsAnalyzer.analyze("Make links safer by blocking SSRF, and faster: p95 under 40 ms");
        assertThat((List<?>) RequirementsAnalyzer.parse(arts.get("req.ambiguities")).get("items")).isEmpty();
    }

    @Test
    void flagsVagueTermsAsAmbiguitiesWithDocumentedAssumptions() {
        Map<String, String> arts = RequirementsAnalyzer.analyze("Make our short links safer and faster.");
        Map<String, Object> amb = RequirementsAnalyzer.parse(arts.get("req.ambiguities"));
        assertThat((List<?>) amb.get("items")).hasSize(2);
        assertThat(RequirementsAnalyzer.parse(arts.get("req.security")).get("hostDenylist")).isEqualTo(true);
        Map<String, Object> perf = RequirementsAnalyzer.parse(arts.get("req.performance"));
        assertThat(perf.get("cacheEnabled")).isEqualTo(true);
        assertThat(perf.get("p95TargetMs")).isEqualTo(50);
    }

    @Test
    void refineAppliesAnswersAndLeavesUntouchedSlicesIdentical() {
        Map<String, String> before = RequirementsAnalyzer.analyze("Make our short links safer and faster.");

        Map<String, String> after = RequirementsAnalyzer.refine(before, Map.of("AMB-1", "HTTPS only, keep the denylist"));

        assertThat(RequirementsAnalyzer.parse(after.get("req.security")).get("allowedSchemes")).isEqualTo(List.of("https"));
        assertThat(after.get("req.performance")).isEqualTo(before.get("req.performance"));
        assertThat(after.get("req.ambiguities")).contains("RESOLVED");
    }

    @Test
    void refineParsesCacheTtlAndLatencyTarget() {
        Map<String, String> before = RequirementsAnalyzer.analyze("Make our short links safer and faster.");
        Map<String, String> after = RequirementsAnalyzer.refine(before, Map.of("AMB-2", "p95 under 20 ms, cache for 120 seconds"));
        Map<String, Object> perf = RequirementsAnalyzer.parse(after.get("req.performance"));
        assertThat(perf.get("cacheTtlSeconds")).isEqualTo(120);
        assertThat(perf.get("p95TargetMs")).isEqualTo(20);
    }

    @Test
    void requirementsAgentNeedsRequirementText() throws Exception {
        NodeSpec n = node("requirements");
        AgentResult r = new RequirementsAgent().execute(ctx(n, scenario("Build a URL shortener with redirect"), new ContextStore()));
        assertThat(r.artifacts()).extracting("id").contains("req.functional", "req.security", "req.performance", "req.ambiguities");
        assertThatThrownBy(() -> new RequirementsAgent().execute(ctx(n, scenario(" "), new ContextStore())))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- architect / risk / review

    @Test
    void architectBuildsOpenApiMatchingTheFeatures() throws Exception {
        ContextStore store = withRequirements("Build a URL shortener with custom aliases, expiry and per-day click analytics, redirect");
        AgentResult r = new ArchitectAgent().execute(ctx(node("architecture"), scenario("x"), store));

        String openapi = r.artifacts().stream().filter(a -> a.id().equals("design.openapi")).findFirst().orElseThrow().content();
        assertThat(openapi).contains("openapi:").contains("expiresAt").contains("clicksByDay").contains("customAlias");
        assertThat(r.changes()).extracting(FileChange::path).contains("docs/design/architecture.md", "docs/design/openapi.yaml");
        String arch = r.artifacts().get(0).content();
        assertThat(arch).contains("## Components").contains("302").contains("Expiry evaluated at read time");
    }

    @Test
    void architectOmitsOptionalContractPartsWhenNotRequested() throws Exception {
        ContextStore store = withRequirements("Build a URL shortener with redirect");
        AgentResult r = new ArchitectAgent().execute(ctx(node("architecture"), scenario("x"), store));
        String openapi = r.artifacts().stream().filter(a -> a.id().equals("design.openapi")).findFirst().orElseThrow().content();
        assertThat(openapi).doesNotContain("expiresAt").doesNotContain("clicksByDay");
    }

    @Test
    void riskAgentCoversFeaturesAndAssumptions() throws Exception {
        ContextStore store = withRequirements("Make our short links safer with redirect and rate limiting");
        AgentResult r = new RiskAgent().execute(ctx(node("risk"), scenario("x"), store));
        String md = r.artifacts().get(0).content();
        assertThat(md).contains("R-SSRF").contains("R-RATE").contains("R-AMB-1").contains("Proceed on assumption");
    }

    @Test
    void reviewAgentListsArtifactVersionsAndOpenAssumptions() throws Exception {
        ContextStore store = withRequirements("Make our short links faster");
        NodeSpec n = node("design_review");
        n.consumes = List.of("req.functional", "req.performance");
        n.produces = List.of("review.design");
        n.gateReason = "sign-off";

        AgentResult r = new ReviewAgent().execute(ctx(n, scenario("x"), store));

        String md = r.artifacts().get(0).content();
        assertThat(r.artifacts().get(0).id()).isEqualTo("review.design");
        assertThat(md).contains("sign-off").contains("req.functional").contains("| v1 |").contains("AMB-1").contains("OPEN");
    }

    // ---- template agent

    @Test
    void templateAgentRendersVariablesFromRequirementArtifacts() throws Exception {
        ContextStore store = withRequirements("Make our short links safer and faster");
        store.put("req.security", "json", RequirementsAnalyzer.json(Map.of("allowedSchemes", List.of("https"), "hostDenylist", true)), "h", "h", Map.of());
        NodeSpec n = node("impl_validator");
        n.params = Map.of("changeset", "v3/validator");
        n.produces = List.of("code.validator");

        AgentResult r = new TemplateAgent("implementer", false).execute(ctx(n, scenario("x"), store));

        FileChange validator = r.changes().get(0);
        assertThat(validator.path()).isEqualTo("src/main/java/com/example/shortener/service/UrlValidator.java");
        assertThat(validator.content()).contains("Set.of(\"https\")").doesNotContain("@@");
        assertThat(r.artifacts().get(0).id()).isEqualTo("code.validator");
    }

    @Test
    void fallbackTemplateAgentIgnoresRequirementDerivedParameters() throws Exception {
        ContextStore store = new ContextStore();
        store.put("req.security", "json", RequirementsAnalyzer.json(Map.of("allowedSchemes", List.of("https"))), "h", "h", Map.of());
        NodeSpec n = node("impl_validator");
        n.params = Map.of("changeset", "v3/validator");

        AgentResult r = new TemplateAgent("implementer.fallback", true).execute(ctx(n, scenario("x"), store));

        assertThat(r.changes().get(0).content()).contains("Set.of(\"http\", \"https\")");
    }

    @Test
    void templateAgentRejectsMissingChangesetAndUnresolvedPlaceholders() {
        assertThatThrownBy(() -> new TemplateAgent("t", false).execute(ctx(node("n"), scenario("x"), new ContextStore())))
                .hasMessageContaining("changeset");
        NodeSpec n = node("n");
        n.params = Map.of("changeset", "does/not/exist");
        assertThatThrownBy(() -> new TemplateAgent("t", false).execute(ctx(n, scenario("x"), new ContextStore())))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TemplateAgent.render("a @@MISSING@@ b", Map.of(), "f.tpl"))
                .hasMessageContaining("unresolved placeholder");
        assertThat(TemplateAgent.render("a @@X@@ b", Map.of("X", "$1\\"), "f.tpl")).isEqualTo("a $1\\ b");
    }

    // ---- impact analysis

    @Test
    void impactAnalysisFindsDirectAndDependantFilesInTheRealCodebase() throws Exception {
        write("src/main/java/com/example/shortener/service/UrlService.java", "package a; class UrlService { UrlValidator v; }");
        write("src/main/java/com/example/shortener/service/UrlValidator.java", "package a; class UrlValidator { }");
        write("src/main/java/com/example/shortener/api/UrlController.java", "package a; class UrlController { UrlService s; CreateUrlRequest r; }");
        write("src/main/java/com/example/shortener/dto/CreateUrlRequest.java", "package a; record CreateUrlRequest() { }");
        write("src/main/java/com/example/shortener/other/Unrelated.java", "package a; class Unrelated { }");
        write("src/main/resources/schema.sql", "CREATE TABLE x (a INT);");
        ContextStore store = withRequirements(
                "Add link expiry (expiresAt) and per-day click analytics. Fix bug: reserved alias check bypass by case.");

        AgentResult r = new ImpactAnalysisAgent().execute(ctx(node("impact_analysis"), scenario("x"), store));

        String md = r.artifacts().get(0).content();
        assertThat(md).contains("UrlService.java").contains("UrlValidator.java").contains("CreateUrlRequest.java")
                .contains("schema.sql");
        assertThat(md.substring(md.indexOf("## Dependants"))).contains("UrlController.java");
        assertThat(md).doesNotContain("Unrelated.java");
        Map<String, Object> summary = RequirementsAnalyzer.parse(r.artifacts().get(1).content());
        assertThat(summary.get("apiContractChange")).isEqualTo(true);
        assertThat(summary.get("schemaChange")).isEqualTo(true);
    }

    @Test
    void impactAnalysisRefusesToRunWithoutAnExistingCodebase() {
        ContextStore store = withRequirements("Add link expiry");
        assertThatThrownBy(() -> new ImpactAnalysisAgent().execute(ctx(node("impact"), scenario("x"), store)))
                .hasMessageContaining("existing codebase");
    }

    // ---- documentation and release

    @Test
    void documentationAgentWritesReadmeAndPrependsChangelogEntry() throws Exception {
        ContextStore store = withRequirements("Build a URL shortener with redirect and analytics");
        new ArchitectAgent().execute(ctx(node("a"), scenario("x"), store)).artifacts()
                .forEach(a -> store.put(a.id(), a.type(), a.content(), "arch", "architect", Map.of()));
        write("CHANGELOG.md", "# Changelog\n\n## 1.0.0 - earlier\n");
        ScenarioSpec sc = scenario("x");
        sc.version = "1.1.0";
        sc.description = "adds things";

        AgentResult r = new DocumentationAgent().execute(ctx(node("docs"), sc, store));

        String readme = r.changes().get(0).content();
        assertThat(readme).contains("`POST /api/v1/urls`").contains("`GET /{code}`").contains("shortener.admin-key");
        String changelog = r.changes().get(1).content();
        assertThat(changelog.indexOf("1.1.0")).isLessThan(changelog.indexOf("1.0.0 - earlier"));
        assertThat(r.changes()).extracting(FileChange::kind).containsOnly(ChangeKind.CREATE);
    }

    @Test
    void releaseAgentRecommendsAccordingToFacts() throws Exception {
        NodeSpec n = node("release_readiness");
        java.util.function.Function<Map<String, Object>, String> verdict = facts -> {
            try {
                TaskContext c = new TaskContext(n, scenario("x"), new ContextStore(), tmp, 1, false, () -> facts);
                String json = new ReleaseAgent().execute(c).artifacts().get(0).content();
                return (String) RequirementsAnalyzer.parse(json).get("verdict");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Map<String, Object> good = facts(true, List.of(), true);
        assertThat(verdict.apply(good)).isEqualTo("GO");
        assertThat(verdict.apply(facts(false, List.of(), true))).isEqualTo("CONDITIONAL");
        assertThat(verdict.apply(facts(true, List.of("AMB-2"), true))).isEqualTo("CONDITIONAL");
        assertThat(verdict.apply(facts(true, List.of(), false))).isEqualTo("NO-GO");
        Map<String, Object> pending = facts(true, List.of(), true);
        pending.put("nodesNotDone", new java.util.ArrayList<>(List.of("docs", "release_readiness")));
        assertThat(verdict.apply(pending)).isEqualTo("NO-GO");
    }

    private static Map<String, Object> facts(boolean tests, List<String> openAmb, boolean auditOk) {
        Map<String, Object> f = new java.util.LinkedHashMap<>();
        f.put("nodesNotDone", new java.util.ArrayList<>(List.of("release_readiness")));
        f.put("testsExecuted", tests);
        f.put("policyBlocks", 0);
        f.put("retries", 0);
        f.put("rollbacks", 0);
        f.put("replans", 0);
        f.put("openAmbiguities", openAmb);
        f.put("auditIntact", auditOk);
        f.put("auditDetail", "x");
        return f;
    }

    private void write(String rel, String content) throws IOException {
        Path p = tmp.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void taskContextReadsTargetFilesSafely() throws IOException {
        write("a/b.txt", "hello");
        TaskContext c = ctx(node("n"), scenario("x"), new ContextStore());
        assertThat(c.readTarget("a/b.txt")).contains("hello");
        assertThat(c.readTarget("missing.txt")).isEmpty();
        assertThat(c.readTarget("../outside.txt")).isEmpty();
        assertThat(c.param("k", "dflt")).isEqualTo("dflt");
        assertThat(c.attempt()).isEqualTo(1);
        assertThat(c.fallback()).isFalse();
    }

    @Test
    void registryResolvesDefaultsAndRejectsUnknownAgents() {
        AgentRegistry reg = AgentRegistry.defaults();
        assertThat(reg.get("requirements")).isInstanceOf(RequirementsAgent.class);
        assertThat(reg.get("implementer.fallback")).isInstanceOf(TemplateAgent.class);
        assertThatThrownBy(() -> reg.get("ghost")).hasMessageContaining("unknown agent");
    }
}

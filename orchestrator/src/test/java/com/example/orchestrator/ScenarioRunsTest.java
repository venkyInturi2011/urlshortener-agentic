package com.example.orchestrator;

import com.example.orchestrator.agent.AgentRegistry;
import com.example.orchestrator.approval.AutoApproveGate;
import com.example.orchestrator.approval.ScriptedApprovalGate;
import com.example.orchestrator.audit.AuditVerifier;
import com.example.orchestrator.cli.Main;
import com.example.orchestrator.engine.EngineConfig;
import com.example.orchestrator.engine.RunResult;
import com.example.orchestrator.engine.WorkflowEngine;
import com.example.orchestrator.model.NodeStatus;
import com.example.orchestrator.model.ScenarioSpec;
import com.example.orchestrator.policy.PolicyEngine;
import com.example.orchestrator.resilience.FaultInjector;
import com.example.orchestrator.validate.ValidatorRegistry;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end runs of the three shipped scenarios against real files, chained like the demo
 * (greenfield, then brownfield on its output, then ambiguous on that). Maven builds are skipped here;
 * the generated service is built and tested for real by the demo run and recorded in docs/.
 */
class ScenarioRunsTest {

    @TempDir Path tmp;

    private static ScenarioSpec load(String name) throws IOException {
        try (InputStream in = ScenarioRunsTest.class.getResourceAsStream("/scenarios/" + name + ".yaml")) {
            return new YAMLMapper().readValue(in, ScenarioSpec.class);
        }
    }

    private RunResult run(String scenario, Path target, FaultInjector faults) throws IOException {
        ScenarioSpec sc = load(scenario);
        EngineConfig cfg = EngineConfig.defaults(tmp.resolve("run-" + scenario), target).withSkipBuild(true).withBackoffMs(1);
        return new WorkflowEngine(sc, cfg, AgentRegistry.defaults(), ValidatorRegistry.defaults(), PolicyEngine.defaults(),
                new ScriptedApprovalGate(sc.approvals), faults, scenario + "-test").run();
    }

    private String read(Path target, String rel) throws IOException {
        return Files.readString(target.resolve(rel));
    }

    @Test
    void greenfieldThenBrownfieldThenAmbiguous() throws Exception {
        // ---------------- greenfield
        Path v1 = tmp.resolve("v1");
        RunResult g = run("greenfield", v1, FaultInjector.none());
        assertThat(g.completed()).as(g.haltReason()).isTrue();
        assertThat(g.nodes().values()).allMatch(s -> s.status == NodeStatus.DONE);
        assertThat(g.metrics().get("approvals_requested")).isEqualTo(2);
        assertThat(v1.resolve("pom.xml")).exists();
        assertThat(v1.resolve("src/main/java/com/example/shortener/service/UrlService.java")).exists();
        assertThat(v1.resolve("src/test/java/com/example/shortener/service/UrlServiceTest.java")).exists();
        assertThat(v1.resolve("docs/design/openapi.yaml")).exists();
        assertThat(read(v1, "docs/release/readiness.md")).contains("CONDITIONAL").contains("build/test validators were skipped");
        assertThat(AuditVerifier.verify(tmp.resolve("run-greenfield/audit.jsonl")).valid()).isTrue();
        assertThat(read(v1, "src/main/java/com/example/shortener/service/UrlService.java")).doesNotContain("expiresAt");

        // ---------------- brownfield on a copy of the greenfield output
        Path v2 = tmp.resolve("v2");
        TestKit.copyTree(v1, v2);
        RunResult b = run("brownfield", v2, FaultInjector.none());
        assertThat(b.completed()).as(b.haltReason()).isTrue();
        assertThat(b.metrics().get("approvals_requested")).as("design gate + schema + contract + release").isEqualTo(4);
        assertThat(read(v2, "src/main/java/com/example/shortener/domain/ShortUrl.java")).contains("expiresAt");
        assertThat(read(v2, "src/main/resources/schema.sql")).contains("ADD COLUMN IF NOT EXISTS expires_at");
        assertThat(read(v2, "src/main/java/com/example/shortener/service/UrlValidator.java")).contains("toLowerCase(Locale.ROOT)");
        assertThat(read(v2, "docs/design/impact-analysis.md")).contains("UrlController.java").contains("API contract change: **true**");
        assertThat(read(v2, "CHANGELOG.md")).contains("1.1.0");
        assertThat(read(v1, "src/main/java/com/example/shortener/domain/ShortUrl.java")).as("v1 copy untouched").doesNotContain("expiresAt");

        // ---------------- ambiguous on a copy of the brownfield output: assumptions, clarification, re-plan
        Path v3 = tmp.resolve("v3");
        TestKit.copyTree(v2, v3);
        RunResult a = run("ambiguous", v3, FaultInjector.none());
        assertThat(a.completed()).as(a.haltReason()).isTrue();
        assertThat(a.metrics().get("replans")).isEqualTo(1);
        assertThat(a.nodes().get("impl_validator").runs).isEqualTo(2);
        assertThat(a.nodes().get("impl_cache").runs).as("unaffected node is not re-run").isEqualTo(1);
        assertThat(a.nodes().get("design_review").runs).as("changed requirement needs re-approval").isEqualTo(2);
        assertThat(read(v3, "src/main/java/com/example/shortener/service/UrlValidator.java")).contains("Set.of(\"https\")");
        assertThat(read(v3, "src/main/java/com/example/shortener/service/RedirectCache.java")).contains("shortener.cache.ttl-seconds:60");
        assertThat(read(v3, "src/test/java/com/example/shortener/service/UrlValidatorTest.java"))
                .contains("assertThatThrownBy(() -> validator.validateUrl(\"http://example.com/a\"))");
        assertThat(read(v3, "docs/release/readiness.md")).contains("AMB-2");
        List<String> audit = Files.readAllLines(tmp.resolve("run-ambiguous/audit.jsonl"));
        assertThat(audit).anyMatch(l -> l.contains("\"type\":\"EVENT_APPLIED\"")).anyMatch(l -> l.contains("\"type\":\"REPLAN\""));
        assertThat(AuditVerifier.verify(tmp.resolve("run-ambiguous/audit.jsonl")).valid()).isTrue();
    }

    @Test
    void brownfieldRecoversFromInjectedBadOutputViaRollbackAndRetry() throws Exception {
        Path v1 = tmp.resolve("v1");
        assertThat(run("greenfield", v1, FaultInjector.none()).completed()).isTrue();
        Path v2 = tmp.resolve("v2");
        TestKit.copyTree(v1, v2);

        RunResult b = run("brownfield", v2, new FaultInjector(List.of(FaultInjector.parse("impl_service_logic:1:bad-output"))));

        assertThat(b.completed()).isTrue();
        assertThat(b.nodes().get("impl_service_logic").attempts).isEqualTo(2);
        assertThat(b.metrics().get("rollbacks")).isEqualTo(1);
        assertThat(b.metrics().get("recovered_incidents")).isEqualTo(1);
        assertThat(read(v2, "src/main/java/com/example/shortener/service/UrlService.java")).doesNotContain("injected");
    }

    @Test
    void brownfieldHaltsSafelyWhenPrimaryAndFallbackBothFail() throws Exception {
        Path v1 = tmp.resolve("v1");
        assertThat(run("greenfield", v1, FaultInjector.none()).completed()).isTrue();
        Path v2 = tmp.resolve("v2");
        TestKit.copyTree(v1, v2);

        RunResult b = run("brownfield", v2, new FaultInjector(List.of(FaultInjector.parse("impl_persistence:99:exception:both"))));

        assertThat(b.status()).isEqualTo("HALTED");
        assertThat(b.nodes().get("impl_persistence").status).isEqualTo(NodeStatus.FAILED);
        assertThat(b.nodes().get("impl_persistence").usedFallback).isTrue();
        assertThat(b.nodes().get("impl_service_logic").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(b.nodes().get("release_readiness").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(read(v2, "src/main/java/com/example/shortener/domain/ShortUrl.java")).as("no partial change applied").doesNotContain("expiresAt");
    }

    @Test
    void deniedApprovalStopsBrownfieldBeforeAnyCodeChanges() throws Exception {
        Path v1 = tmp.resolve("v1");
        assertThat(run("greenfield", v1, FaultInjector.none()).completed()).isTrue();
        Path v2 = tmp.resolve("v2");
        TestKit.copyTree(v1, v2);
        ScenarioSpec sc = load("brownfield");
        EngineConfig cfg = EngineConfig.defaults(tmp.resolve("run-denied"), v2).withSkipBuild(true).withBackoffMs(1);

        RunResult r = new WorkflowEngine(sc, cfg, AgentRegistry.defaults(), ValidatorRegistry.defaults(), PolicyEngine.defaults(),
                new ScriptedApprovalGate(Map.of("*", "deny")), FaultInjector.none(), "denied").run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.nodes().get("design_review").status).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodes().get("impl_persistence").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(read(v2, "src/main/java/com/example/shortener/domain/ShortUrl.java")).doesNotContain("expiresAt");
    }

    @Test
    void autoApproveGateIsUsableAsAScenarioGate() throws Exception {
        ScenarioSpec sc = load("greenfield");
        EngineConfig cfg = EngineConfig.defaults(tmp.resolve("run"), tmp.resolve("t")).withSkipBuild(true);
        RunResult r = new WorkflowEngine(sc, cfg, AgentRegistry.defaults(), ValidatorRegistry.defaults(), PolicyEngine.defaults(),
                new AutoApproveGate(), FaultInjector.none(), "auto").run();
        assertThat(r.completed()).isTrue();
    }

    @Test
    void cliRunsAScenarioAndVerifiesItsAudit() throws Exception {
        Main main = new Main();
        Path runDir = tmp.resolve("cli-run");
        int rc = main.execute(new String[]{"run", "--scenario", "greenfield", "--target", tmp.resolve("cli-target").toString(),
                "--run-dir", runDir.toString(), "--skip-build", "--approve", "auto"});
        assertThat(rc).isZero();
        assertThat(main.execute(new String[]{"verify-audit", "--run-dir", runDir.toString()})).isZero();
        assertThat(runDir.resolve("report.md")).exists();
        assertThat(runDir.resolve("lineage.json")).exists();

        Files.writeString(runDir.resolve("audit.jsonl"), Files.readString(runDir.resolve("audit.jsonl")).replace("RUN_STARTED", "RUN_STARTEX"));
        assertThat(main.execute(new String[]{"verify-audit", "--run-dir", runDir.toString()})).isEqualTo(3);
    }

    @Test
    void cliCopiesBaselineInjectsFaultsAndResumesAHaltedRun() throws Exception {
        Main main = new Main();
        Path g = tmp.resolve("g");
        assertThat(main.execute(new String[]{"run", "--scenario", "greenfield", "--target", g.toString(),
                "--run-dir", tmp.resolve("gr").toString(), "--skip-build", "--approve", "auto"})).isZero();

        Path b = tmp.resolve("b");
        Path runDir = tmp.resolve("br");
        int halted = main.execute(new String[]{"run", "--scenario", "brownfield", "--copy-from", g.toString(), "--target", b.toString(),
                "--run-dir", runDir.toString(), "--skip-build", "--approve", "auto", "--max-retries", "0", "--parallelism", "2",
                "--inject-failure", "impl_persistence:5:exception:both"});
        assertThat(halted).isEqualTo(2);

        int resumed = main.execute(new String[]{"resume", "--run-dir", runDir.toString()});
        assertThat(resumed).isZero();
        assertThat(read(b, "src/main/java/com/example/shortener/domain/ShortUrl.java")).contains("expiresAt");
        assertThat(AuditVerifier.verify(runDir.resolve("audit.jsonl")).valid()).isTrue();
    }

    @Test
    void cliRejectsBadUsage() throws Exception {
        Main main = new Main();
        assertThat(main.execute(new String[]{})).isEqualTo(1);
        assertThat(main.execute(new String[]{"bogus"})).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> main.execute(new String[]{"run", "--target", "x"}))
                .hasMessageContaining("missing --scenario");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> main.execute(new String[]{"run", "stray"}))
                .hasMessageContaining("unexpected argument");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> main.execute(new String[]{"run", "--scenario", "no-such", "--target", "x"}))
                .hasMessageContaining("unknown scenario");
        assertThat(main.execute(new String[]{"graph", "--scenario", "ambiguous"})).isZero();
    }

    @Test
    void shippedScenariosAreWellFormedGraphs() throws Exception {
        for (String name : List.of("greenfield", "brownfield", "ambiguous")) {
            ScenarioSpec sc = load(name);
            com.example.orchestrator.graph.WorkflowGraph g = new com.example.orchestrator.graph.WorkflowGraph(sc.nodes);
            AgentRegistry agents = AgentRegistry.defaults();
            ValidatorRegistry validators = ValidatorRegistry.defaults();
            sc.nodes.forEach(n -> {
                agents.get(n.agent);
                if (n.fallbackAgent != null) agents.get(n.fallbackAgent);
                n.exitGate.forEach(validators::get);
            });
            assertThat(g.layers().size()).as(name).isGreaterThan(3);
            assertThat(g.joins()).as(name + " has synchronisation points").isNotEmpty();
            assertThat(sc.nodes.stream().filter(n -> n.humanGate)).as(name + " has human gates").hasSizeGreaterThanOrEqualTo(2);
        }
    }
}

package com.example.orchestrator.engine;

import com.example.orchestrator.agent.Agent;
import com.example.orchestrator.agent.AgentRegistry;
import com.example.orchestrator.agent.RequirementsAgent;
import com.example.orchestrator.approval.ApprovalGate;
import com.example.orchestrator.audit.AuditVerifier;
import com.example.orchestrator.model.*;
import com.example.orchestrator.resilience.FaultInjector;
import com.example.orchestrator.validate.Validator;
import com.example.orchestrator.validate.ValidatorRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.orchestrator.TestKit.*;
import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEngineTest {

    @TempDir Path tmp;

    private Path target() { return tmp.resolve("target"); }

    private Path run() { return tmp.resolve("run"); }

    // ------------------------------------------------------------ parallelism and joins

    @Test
    void independentNodesRunInParallelAndJoinWaitsForAll() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        List<String> finished = Collections.synchronizedList(new ArrayList<>());
        Agent root = agent("root", c -> ok("art.a"));
        Agent par = agent("par", c -> {
            bothRunning.countDown();
            if (!bothRunning.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("nodes did not overlap");
            finished.add(c.node().id);
            return ok("art." + c.node().id);
        });
        Agent join = agent("join", c -> {
            if (!finished.containsAll(List.of("b", "c"))) throw new IllegalStateException("join started early");
            return ok("art.d");
        });
        ScenarioSpec sc = scenario(node("a", "root"), node("b", "par", "a"), node("c", "par", "a"), node("d", "join", "b", "c"));

        RunResult r = engine(sc, cfg(tmp), registry(root, par, join)).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.nodes().values()).allMatch(s -> s.status == NodeStatus.DONE);
        assertThat(r.metrics().get("node_success_rate")).isEqualTo(1.0);
        assertThat(AuditVerifier.verify(run().resolve("audit.jsonl")).valid()).isTrue();
        assertThat(run().resolve("report.md")).exists();
        assertThat(run().resolve("metrics.json")).exists();
    }

    // ------------------------------------------------------------ retries, fallback, rollback

    @Test
    void transientFailuresAreRetriedAndRecoveryTimeIsRecorded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = agent("flaky", c -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("boom " + calls.get());
            return ok("art.a");
        });

        RunResult r = engine(scenario(node("a", "flaky")), cfg(tmp), registry(flaky)).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.nodes().get("a").attempts).isEqualTo(3);
        assertThat(r.metrics().get("retries")).isEqualTo(2);
        assertThat(r.metrics().get("failed_attempts")).isEqualTo(2);
        assertThat(r.metrics().get("recovered_incidents")).isEqualTo(1);
        assertThat(r.metrics().get("mttr_ms")).isNotNull();
        assertThat(r.metrics().get("first_attempt_success_rate")).isEqualTo(0.0);
    }

    @Test
    void retriesAreBounded() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Agent bad = agent("bad", c -> { calls.incrementAndGet(); throw new IllegalStateException("always"); });
        NodeSpec a = node("a", "bad");
        a.maxRetries = 1;

        RunResult r = engine(scenario(a), cfg(tmp), registry(bad)).run();

        assertThat(calls.get()).isEqualTo(2);
        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.FAILED);
    }

    @Test
    void failedExitGateRollsBackFilesThenRetrySucceeds() throws Exception {
        AtomicInteger validations = new AtomicInteger();
        AtomicBoolean fileExistedDuringGate = new AtomicBoolean();
        Validator gate = new Validator() {
            @Override public String name() { return "gate"; }
            @Override public Result validate(Context ctx) {
                fileExistedDuringGate.set(Files.exists(ctx.targetDir().resolve("out/a.txt")));
                return validations.incrementAndGet() == 1 ? Result.fail("first time bad") : Result.pass("ok");
            }
        };
        Agent writer = agent("writer", c -> ok("art.a", file("out/a.txt", "data")));
        NodeSpec a = node("a", "writer");
        a.exitGate = List.of("gate");

        RunResult r = engine(scenario(a), cfg(tmp), registry(writer), new com.example.orchestrator.approval.AutoApproveGate(),
                FaultInjector.none(), ValidatorRegistry.defaults().register(gate)).run();

        assertThat(r.completed()).isTrue();
        assertThat(fileExistedDuringGate).isTrue();
        assertThat(Files.readString(target().resolve("out/a.txt"))).endsWith("data");
        assertThat(r.metrics().get("rollbacks")).isEqualTo(1);
        assertThat(auditHas(run(), "ROLLBACK")).isTrue();
    }

    @Test
    void rollbackRestoresPreviousContentOfExistingFile() throws Exception {
        Files.createDirectories(target().resolve("out"));
        Files.writeString(target().resolve("out/a.txt"), "original");
        Validator reject = new Validator() {
            @Override public String name() { return "reject"; }
            @Override public Result validate(Context ctx) { return Result.fail("never good enough"); }
        };
        Agent writer = agent("writer", c -> ok("art.a", file("out/a.txt", "new content")));
        NodeSpec a = node("a", "writer");
        a.exitGate = List.of("reject");
        a.maxRetries = 0;

        RunResult r = engine(scenario(a), cfg(tmp), registry(writer), new com.example.orchestrator.approval.AutoApproveGate(),
                FaultInjector.none(), ValidatorRegistry.defaults().register(reject)).run();

        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(Files.readString(target().resolve("out/a.txt"))).isEqualTo("original");
    }

    @Test
    void fallbackAgentTakesOverAfterPrimaryExhaustsRetries() throws Exception {
        Agent bad = agent("bad", c -> { throw new IllegalStateException("nope"); });
        Agent good = agent("good", c -> ok("art.a", file("docs/a.md", "from fallback")));
        NodeSpec a = node("a", "bad");
        a.fallbackAgent = "good";

        RunResult r = engine(scenario(a), cfg(tmp), registry(bad, good)).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.nodes().get("a").usedFallback).isTrue();
        assertThat(r.nodes().get("a").attempts).isEqualTo(4); // 3 primary + 1 fallback
        assertThat(r.metrics().get("fallbacks")).isEqualTo(1);
        assertThat(auditHas(run(), "FALLBACK_ENGAGED")).isTrue();
    }

    @Test
    void totalFailureTriggersSafeStopAndSkipsDownstream() throws Exception {
        Agent ok = agent("ok", c -> ok("art." + c.node().id));
        Agent bad = agent("bad", c -> { throw new IllegalStateException("fatal"); });
        NodeSpec b = node("b", "bad", "a");
        b.maxRetries = 1;
        ScenarioSpec sc = scenario(node("a", "ok"), b, node("c", "ok", "b"));

        RunResult r = engine(sc, cfg(tmp), registry(ok, bad)).run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.haltReason()).contains("node b failed");
        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.DONE);
        assertThat(r.nodes().get("b").status).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodes().get("c").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(auditHas(run(), "SAFE_STOP")).isTrue();
        assertThat(r.metrics().get("unrecovered_incidents")).isEqualTo(1);
    }

    @Test
    void attemptBudgetStopsRunawayRetries() throws Exception {
        Agent bad = agent("bad", c -> { throw new IllegalStateException("x"); });
        NodeSpec a = node("a", "bad");
        a.maxRetries = 50;
        EngineConfig cfg = new EngineConfig(run(), target(), true, 2, 1, 4, 60_000, 3, false);

        RunResult r = engine(scenario(a), cfg, registry(bad)).run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.nodes().get("a").attempts).isLessThan(10);
    }

    // ------------------------------------------------------------ governance

    @Test
    void policyViolationIsFatalNotRetriedAndNothingIsWritten() throws Exception {
        Agent leaky = agent("leaky", c -> ok("art.a",
                file("src/main/java/a/Leak.java", "package a;\nclass Leak { String password = \"SuperSecret1234\"; }\n")));

        RunResult r = engine(scenario(node("a", "leaky")), cfg(tmp), registry(leaky)).run();

        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodes().get("a").attempts).isEqualTo(1);
        assertThat(r.nodes().get("a").lastError).contains("policy violation");
        assertThat(target().resolve("src/main/java/a/Leak.java")).doesNotExist();
        assertThat(r.metrics().get("policy_blocks")).isEqualTo(1);
        assertThat(auditHas(run(), "POLICY_BLOCKED")).isTrue();
    }

    @Test
    void humanGateBlocksUntilApprovedAndDenialHaltsTheRun() throws Exception {
        Agent ok = agent("ok", c -> ok("art." + c.node().id, file("docs/" + c.node().id + ".md", "x")));
        NodeSpec gate = node("g", "ok", "a");
        gate.humanGate = true;
        gate.gateReason = "needs sign-off";
        ScenarioSpec sc = scenario(node("a", "ok"), gate, node("z", "ok", "g"));
        List<String> asked = new ArrayList<>();
        ApprovalGate deny = req -> {
            asked.add(req.nodeId() + ":" + req.reason());
            return new ApprovalGate.Decision(false, "alice", "not yet");
        };

        RunResult r = engine(sc, cfg(tmp), registry(ok), deny, FaultInjector.none(), ValidatorRegistry.defaults()).run();

        assertThat(asked).containsExactly("g:needs sign-off");
        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.nodes().get("g").status).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodes().get("z").status).isEqualTo(NodeStatus.SKIPPED);
        assertThat(target().resolve("docs/g.md")).doesNotExist();
        assertThat(r.metrics().get("approvals_denied")).isEqualTo(1);
        assertThat(auditHas(run(), "APPROVAL_DENIED")).isTrue();
    }

    @Test
    void approvedGateProceedsAndIsAudited() throws Exception {
        Agent ok = agent("ok", c -> ok("art.g"));
        NodeSpec gate = node("g", "ok");
        gate.humanGate = true;

        RunResult r = engine(scenario(gate), cfg(tmp), registry(ok),
                req -> new ApprovalGate.Decision(true, "bob", "lgtm"), FaultInjector.none(), ValidatorRegistry.defaults()).run();

        assertThat(r.completed()).isTrue();
        assertThat(auditHas(run(), "APPROVAL_GRANTED")).isTrue();
        assertThat(r.metrics().get("approvals_requested")).isEqualTo(1);
    }

    @Test
    void highImpactChangeToExistingFileRequiresApprovalEvenWithoutAGate() throws Exception {
        Files.createDirectories(target());
        Files.writeString(target().resolve("pom.xml"), "<project/>");
        Agent edit = agent("edit", c -> ok("art.a", file("pom.xml",
                "<project><dependencies><dependency><groupId>org.springframework.boot</groupId></dependency></dependencies></project>")));
        List<ApprovalGate.Request> asked = new ArrayList<>();

        RunResult r = engine(scenario(node("a", "edit")), cfg(tmp), registry(edit), req -> {
            asked.add(req);
            return new ApprovalGate.Decision(true, "carol", "dependency ok");
        }, FaultInjector.none(), ValidatorRegistry.defaults()).run();

        assertThat(r.completed()).isTrue();
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).highImpactPaths()).containsExactly("pom.xml");
        assertThat(asked.get(0).reason()).contains("dependency");
    }

    @Test
    void lowImpactChangesNeedNoApproval() throws Exception {
        Agent ok = agent("ok", c -> ok("art.a", file("docs/a.md", "x")));
        AtomicInteger asked = new AtomicInteger();

        RunResult r = engine(scenario(node("a", "ok")), cfg(tmp), registry(ok), req -> {
            asked.incrementAndGet();
            return new ApprovalGate.Decision(true, "x", "x");
        }, FaultInjector.none(), ValidatorRegistry.defaults()).run();

        assertThat(r.completed()).isTrue();
        assertThat(asked.get()).isZero();
    }

    @Test
    void entryGateFailsWhenDeclaredInputIsMissing() throws Exception {
        Agent ok = agent("ok", c -> ok("art.a"));
        NodeSpec a = node("a", "ok");
        a.consumes = List.of("never.produced");

        RunResult r = engine(scenario(a), cfg(tmp), registry(ok)).run();

        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.FAILED);
        assertThat(r.nodes().get("a").lastError).contains("entry gate failed");
    }

    @Test
    void killSwitchFilePreventsAnyNodeFromStarting() throws Exception {
        Files.createDirectories(run());
        Files.writeString(run().resolve("STOP"), "stop now");
        AtomicInteger ran = new AtomicInteger();
        Agent ok = agent("ok", c -> { ran.incrementAndGet(); return ok("art.a"); });

        RunResult r = engine(scenario(node("a", "ok")), cfg(tmp), registry(ok)).run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.haltReason()).contains("kill switch");
        assertThat(ran.get()).isZero();
        assertThat(r.nodes().get("a").status).isEqualTo(NodeStatus.SKIPPED);
    }

    @Test
    void generatedJavaFilesCarryProvenanceHeader() throws Exception {
        Agent w = agent("w", c -> ok("art.a", file("src/main/java/a/A.java", "package a;\nclass A {}\n")));

        engine(scenario(node("a", "w")), cfg(tmp), registry(w)).run();

        assertThat(Files.readString(target().resolve("src/main/java/a/A.java")))
                .startsWith("// Generated by agentic orchestrator | run test-run | node a");
    }

    // ------------------------------------------------------------ fault injection through the engine

    @Test
    void injectedExceptionIsRecoveredByRetry() throws Exception {
        Agent ok = agent("ok", c -> ok("art.a"));
        FaultInjector faults = new FaultInjector(List.of(FaultInjector.parse("a:1:exception")));

        RunResult r = engine(scenario(node("a", "ok")), cfg(tmp), registry(ok), new com.example.orchestrator.approval.AutoApproveGate(),
                faults, ValidatorRegistry.defaults()).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.nodes().get("a").attempts).isEqualTo(2);
    }

    @Test
    void injectedBadOutputIsCaughtByExitGateRolledBackAndRetried() throws Exception {
        Agent w = agent("w", c -> ok("art.a", file("src/main/java/a/A.java", "package a;\nclass A {}\n")));
        NodeSpec a = node("a", "w");
        a.exitGate = List.of("structure");
        FaultInjector faults = new FaultInjector(List.of(FaultInjector.parse("a:1:bad-output")));

        RunResult r = engine(scenario(a), cfg(tmp), registry(w), new com.example.orchestrator.approval.AutoApproveGate(),
                faults, ValidatorRegistry.defaults()).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.metrics().get("rollbacks")).isEqualTo(1);
        assertThat(Files.readString(target().resolve("src/main/java/a/A.java"))).doesNotContain("injected");
    }

    @Test
    void injectedPolicyViolationHaltsWithoutWriting() throws Exception {
        Agent w = agent("w", c -> ok("art.a", file("docs/a.md", "x")));
        FaultInjector faults = new FaultInjector(List.of(FaultInjector.parse("a:1:policy")));

        RunResult r = engine(scenario(node("a", "w")), cfg(tmp), registry(w), new com.example.orchestrator.approval.AutoApproveGate(),
                faults, ValidatorRegistry.defaults()).run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(target().resolve("src/main/java/com/example/shortener/Injected.java")).doesNotExist();
        assertThat(target().resolve("docs/a.md")).doesNotExist();
    }

    @Test
    void faultOnPrimaryAndFallbackExhaustsEverythingAndHalts() throws Exception {
        Agent p = agent("p", c -> ok("art.a"));
        Agent f = agent("f", c -> ok("art.a"));
        NodeSpec a = node("a", "p");
        a.fallbackAgent = "f";
        FaultInjector faults = new FaultInjector(List.of(FaultInjector.parse("a:99:exception:both")));

        RunResult r = engine(scenario(a), cfg(tmp), registry(p, f), new com.example.orchestrator.approval.AutoApproveGate(),
                faults, ValidatorRegistry.defaults()).run();

        assertThat(r.status()).isEqualTo("HALTED");
        assertThat(r.nodes().get("a").usedFallback).isTrue();
    }

    // ------------------------------------------------------------ resume

    @Test
    void haltedRunCanBeResumedWithoutRepeatingCompletedWork() throws Exception {
        AtomicInteger aRuns = new AtomicInteger(), bRuns = new AtomicInteger();
        AtomicBoolean broken = new AtomicBoolean(true);
        Agent a = agent("a", c -> { aRuns.incrementAndGet(); return ok("art.a", file("docs/a.md", "a")); });
        Agent b = agent("b", c -> {
            bRuns.incrementAndGet();
            if (broken.get()) throw new IllegalStateException("dependency down");
            return ok("art.b", file("docs/b.md", "b"));
        });
        NodeSpec nb = node("b", "b", "a");
        nb.maxRetries = 0;
        ScenarioSpec sc = scenario(node("a", "a"), nb);
        AgentRegistry agents = registry(a, b);

        RunResult first = engine(sc, cfg(tmp), agents).run();
        assertThat(first.status()).isEqualTo("HALTED");
        assertThat(first.nodes().get("b").status).isEqualTo(NodeStatus.FAILED);

        broken.set(false);
        RunResult second = engine(sc, cfg(tmp).withResume(true), agents).run();

        assertThat(second.completed()).isTrue();
        assertThat(aRuns.get()).isEqualTo(1);
        assertThat(bRuns.get()).isEqualTo(2);
        assertThat(auditHas(run(), "RUN_RESUMED")).isTrue();
        assertThat(AuditVerifier.verify(run().resolve("audit.jsonl")).valid()).isTrue();
    }

    // ------------------------------------------------------------ dynamic re-planning

    @Test
    void clarificationReplansOnlyTheAffectedSubgraph() throws Exception {
        AtomicInteger aRuns = new AtomicInteger(), bRuns = new AtomicInteger(), cRuns = new AtomicInteger();
        Agent a = agent("a", c -> ok("art.a", file("docs/a.md", "run" + aRuns.incrementAndGet())));
        Agent b = agent("b", c -> ok("art.b", file("docs/b.md", "run" + bRuns.incrementAndGet())));
        Agent cc = agent("c", c -> ok("art.c", file("docs/c.md", "run" + cRuns.incrementAndGet())));
        NodeSpec req = node("req", "requirements");
        req.produces = List.of("req.functional", "req.security", "req.performance", "req.ambiguities");
        NodeSpec na = node("a", "a", "req");
        na.consumes = List.of("req.security");
        NodeSpec nb = node("b", "b", "req");
        nb.consumes = List.of("req.performance");
        NodeSpec nc = node("c", "c", "a", "b");
        nc.consumes = List.of("art.a", "art.b");
        ScenarioSpec sc = scenario(req, na, nb, nc);
        sc.requirement = "Make our short links safer and faster.";
        ScenarioSpec.EventSpec ev = new ScenarioSpec.EventSpec();
        ev.afterNode = "c";
        ev.type = "clarification";
        ev.answers = java.util.Map.of("AMB-1", "HTTPS only please");
        sc.events = List.of(ev);

        RunResult r = engine(sc, cfg(tmp), registry(new RequirementsAgent(), a, b, cc)).run();

        assertThat(r.completed()).isTrue();
        assertThat(aRuns.get()).as("a consumes the changed req.security").isEqualTo(2);
        assertThat(cRuns.get()).as("c consumes a's output").isEqualTo(2);
        assertThat(bRuns.get()).as("b is unaffected and keeps its result").isEqualTo(1);
        assertThat(r.metrics().get("replans")).isEqualTo(1);
        assertThat(Files.readString(target().resolve("docs/a.md"))).isEqualTo("run2");
        assertThat(Files.readString(target().resolve("docs/b.md"))).isEqualTo("run1");
        assertThat(auditHas(run(), "REPLAN")).isTrue();
        assertThat(r.nodes().get("a").runs).isEqualTo(2);
        assertThat(r.nodes().get("b").runs).isEqualTo(1);
    }

    @Test
    void clarificationThatChangesNothingDoesNotReplan() throws Exception {
        Agent ok = agent("ok", c -> ok("art." + c.node().id));
        NodeSpec req = node("req", "requirements");
        req.produces = List.of("req.functional", "req.security", "req.performance", "req.ambiguities");
        ScenarioSpec sc = scenario(req, node("a", "ok", "req"));
        sc.requirement = "Make our short links safer and faster.";
        ScenarioSpec.EventSpec ev = new ScenarioSpec.EventSpec();
        ev.afterNode = "a";
        ev.type = "clarification";
        ev.answers = java.util.Map.of("AMB-99", "no such question");
        sc.events = List.of(ev);

        RunResult r = engine(sc, cfg(tmp), registry(new RequirementsAgent(), ok)).run();

        assertThat(r.completed()).isTrue();
        assertThat(r.metrics().get("replans")).isEqualTo(0);
    }

    @Test
    void unknownEventTypeIsRejected() {
        Agent ok = agent("ok", c -> ok("art.a"));
        ScenarioSpec sc = scenario(node("a", "ok"));
        ScenarioSpec.EventSpec ev = new ScenarioSpec.EventSpec();
        ev.afterNode = "a";
        ev.type = "mystery";
        sc.events = List.of(ev);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine(sc, cfg(tmp), registry(ok)).run())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void engineExposesGraphAndContext() throws IOException {
        Agent ok = agent("ok", c -> ok("art.a"));
        WorkflowEngine e = engine(scenario(node("a", "ok")), cfg(tmp), registry(ok));
        e.run();
        assertThat(e.graph().contains("a")).isTrue();
        assertThat(e.context().has("art.a")).isTrue();
        assertThat(e.audit().traceId()).isEqualTo("test-run");
    }
}

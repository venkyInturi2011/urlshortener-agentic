package com.example.orchestrator;

import com.example.orchestrator.approval.*;
import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.metrics.MetricsCollector;
import com.example.orchestrator.model.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Context store, metrics and approval gates. */
class SupportTest {

    @TempDir Path tmp;

    // ---- ContextStore

    @Test
    void identicalContentDoesNotBumpTheVersionButChangedContentDoes() {
        ContextStore s = new ContextStore();
        Artifact v1 = s.put("a", "text", "one", "n1", "ag", Map.of());
        Artifact same = s.put("a", "text", "one", "n2", "ag", Map.of());
        Artifact v2 = s.put("a", "text", "two", "n2", "ag", Map.of("x", 1));

        assertThat(v1.version()).isEqualTo(1);
        assertThat(same).isSameAs(v1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.consumed()).containsEntry("x", 1);
        assertThat(s.history("a")).hasSize(2);
        assertThat(s.content("a")).isEqualTo("two");
        assertThat(s.version("a")).isEqualTo(2);
        assertThat(s.version("none")).isZero();
        assertThat(s.has("a")).isTrue();
        assertThat(s.ids()).containsExactly("a");
        assertThatThrownBy(() -> s.content("none")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void persistsAndReloadsHistory() throws Exception {
        ContextStore s = new ContextStore();
        s.put("a", "text", "one", "n", "ag", Map.of());
        s.put("a", "text", "two", "n", "ag", Map.of());
        s.save(tmp.resolve("ctx.json"));

        ContextStore loaded = new ContextStore();
        loaded.load(tmp.resolve("ctx.json"));
        loaded.load(tmp.resolve("absent.json")); // absent file is a no-op

        assertThat(loaded.history("a")).hasSize(2);
        assertThat(loaded.content("a")).isEqualTo("two");
        assertThat(loaded.latest("a").orElseThrow().sha256()).hasSize(64);
    }

    // ---- MetricsCollector

    @Test
    @SuppressWarnings("unchecked")
    void computesSuccessRatesRetriesAndMttr() throws Exception {
        MetricsCollector m = new MetricsCollector();
        m.runStarted();
        m.attempt("a", true);
        m.nodeDone("a", 1, 10);
        m.attempt("b", false);
        m.retry();
        m.rollback();
        Thread.sleep(5);
        m.attempt("b", true);
        m.nodeDone("b", 2, 30);
        m.attempt("c", false); // never recovers
        m.fallback();
        m.policyBlock();
        m.replan();
        m.approval(true, 7);
        m.approval(false, 3);
        m.finish(3, 2);

        Map<String, Object> s = m.snapshot();

        assertThat(s.get("nodes_total")).isEqualTo(3);
        assertThat(s.get("nodes_done")).isEqualTo(2);
        assertThat((double) s.get("node_success_rate")).isEqualTo(0.667);
        assertThat((double) s.get("first_attempt_success_rate")).isEqualTo(0.5);
        assertThat(s.get("attempts")).isEqualTo(4);
        assertThat(s.get("failed_attempts")).isEqualTo(2);
        assertThat(s.get("retries")).isEqualTo(1);
        assertThat(s.get("rollbacks")).isEqualTo(1);
        assertThat(s.get("fallbacks")).isEqualTo(1);
        assertThat(s.get("policy_blocks")).isEqualTo(1);
        assertThat(s.get("replans")).isEqualTo(1);
        assertThat(s.get("approvals_requested")).isEqualTo(2);
        assertThat(s.get("approvals_denied")).isEqualTo(1);
        assertThat(s.get("approval_wait_ms")).isEqualTo(10L);
        assertThat(s.get("recovered_incidents")).isEqualTo(1);
        assertThat(s.get("unrecovered_incidents")).isEqualTo(1);
        assertThat((long) s.get("mttr_ms")).isGreaterThanOrEqualTo(0);
        assertThat((long) s.get("e2e_latency_ms")).isGreaterThanOrEqualTo(5);
        assertThat(((Map<String, Object>) s.get("node_latency_ms")).keySet()).contains("a", "b");
    }

    @Test
    void emptyMetricsAreWellDefined() {
        Map<String, Object> s = new MetricsCollector().snapshot();
        assertThat(s.get("mttr_ms")).isNull();
        assertThat(s.get("node_success_rate")).isEqualTo(0.0);
    }

    // ---- approval gates

    private static ApprovalGate.Request request() {
        return new ApprovalGate.Request("node1", "reason", "summary text", List.of("pom.xml"));
    }

    @Test
    void autoGateApprovesAndScriptedGateFollowsTheScript() {
        assertThat(new AutoApproveGate().request(request()).approved()).isTrue();

        ScriptedApprovalGate scripted = new ScriptedApprovalGate(Map.of("node1", "deny", "*", "approve"));
        assertThat(scripted.request(request()).approved()).isFalse();
        assertThat(scripted.request(new ApprovalGate.Request("other", "r", "s", List.of())).approved()).isTrue();
        assertThat(new ScriptedApprovalGate(Map.of()).request(request()).approved()).as("default is deny").isFalse();
    }

    private ApprovalGate.Decision console(String input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleApprovalGate gate = new ConsoleApprovalGate(new ByteArrayInputStream(input.getBytes()), new PrintStream(out));
        ApprovalGate.Decision d = gate.request(request());
        assertThat(out.toString()).contains("APPROVAL REQUIRED: node1").contains("summary text").contains("pom.xml");
        return d;
    }

    @Test
    void consoleGateFailsClosed() {
        assertThat(console("y\n").approved()).isTrue();
        assertThat(console("YES\n").approved()).isTrue();
        assertThat(console("n\n").approved()).isFalse();
        assertThat(console("maybe\n").approved()).isFalse();
        assertThat(console("").approved()).as("EOF").isFalse();
    }
}

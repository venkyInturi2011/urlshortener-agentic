package com.example.orchestrator.engine;

import com.example.orchestrator.agent.AgentRegistry;
import com.example.orchestrator.agent.RequirementsAnalyzer;
import com.example.orchestrator.agent.TaskContext;
import com.example.orchestrator.approval.ApprovalGate;
import com.example.orchestrator.audit.AuditLog;
import com.example.orchestrator.audit.AuditVerifier;
import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.context.Hashing;
import com.example.orchestrator.graph.WorkflowGraph;
import com.example.orchestrator.metrics.MetricsCollector;
import com.example.orchestrator.model.*;
import com.example.orchestrator.policy.ChangeClassifier;
import com.example.orchestrator.policy.PolicyEngine;
import com.example.orchestrator.policy.PolicyReport;
import com.example.orchestrator.policy.Violation;
import com.example.orchestrator.resilience.FaultInjector;
import com.example.orchestrator.resilience.SafeStop;
import com.example.orchestrator.resilience.SnapshotStore;
import com.example.orchestrator.validate.Validator;
import com.example.orchestrator.validate.ValidatorRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stateful, graph-driven orchestrator. A ready-set scheduler runs independent nodes in parallel and joins at
 * synchronisation nodes. Every node passes: entry gate, agent, policy, (human approval), snapshot+apply,
 * exit-gate validators, publish. Failures are retried (bounded), fall back, roll back and finally safe-stop.
 */
public final class WorkflowEngine {
    private record Attempt(boolean ok, boolean nonRetryable, String error) {
        static Attempt success() { return new Attempt(true, false, null); }
        static Attempt retryable(String e) { return new Attempt(false, false, e); }
        static Attempt fatal(String e) { return new Attempt(false, true, e); }
    }

    private static final ObjectMapper OM = new ObjectMapper();

    private final ScenarioSpec scenario;
    private final EngineConfig cfg;
    private final AgentRegistry agents;
    private final ValidatorRegistry validators;
    private final PolicyEngine policy;
    private final ApprovalGate approvals;
    private final FaultInjector faults;
    private final WorkflowGraph graph;
    private final String runId;

    private final ContextStore context = new ContextStore();
    private final MetricsCollector metrics = new MetricsCollector();
    private final Map<String, NodeState> states = new LinkedHashMap<>();
    private final Set<Integer> firedEvents = new LinkedHashSet<>();
    private final Set<String> approvedKeys = ConcurrentHashMap.newKeySet();
    private final Object approvalLock = new Object();
    private final AtomicBoolean testsExecuted = new AtomicBoolean(false);
    private final AtomicInteger policyBlocks = new AtomicInteger();
    private final AtomicInteger totalAttempts = new AtomicInteger();
    private final List<Map<String, Object>> lineage = Collections.synchronizedList(new ArrayList<>());
    private final List<Map<String, Object>> decisions = Collections.synchronizedList(new ArrayList<>());
    private final AuditLog audit;
    private final SnapshotStore snapshots;
    private final SafeStop safeStop;
    private final Replanner replanner = new Replanner();

    public WorkflowEngine(ScenarioSpec scenario, EngineConfig cfg, AgentRegistry agents, ValidatorRegistry validators,
                          PolicyEngine policy, ApprovalGate approvals, FaultInjector faults, String runId) {
        this.scenario = scenario;
        this.cfg = cfg;
        this.agents = agents;
        this.validators = validators;
        this.policy = policy;
        this.approvals = approvals;
        this.faults = faults;
        this.runId = runId;
        this.graph = new WorkflowGraph(scenario.nodes);
        this.audit = new AuditLog(cfg.runDir().resolve("audit.jsonl"), runId);
        this.snapshots = new SnapshotStore(cfg.targetDir(), cfg.runDir().resolve("snapshots"));
        this.safeStop = new SafeStop(cfg.runDir().resolve("STOP"));
        for (NodeSpec n : graph.nodes()) states.put(n.id, new NodeState());
    }

    public WorkflowGraph graph() { return graph; }

    public ContextStore context() { return context; }

    public AuditLog audit() { return audit; }

    public RunResult run() throws IOException {
        Files.createDirectories(cfg.targetDir());
        Files.createDirectories(cfg.runDir());
        if (cfg.resume()) loadState();
        metrics.runStarted();
        audit("RUN_STARTED", "orchestrator", null, mapOf("scenario", scenario.name, "resumed", cfg.resume(),
                "nodes", graph.nodes().size(), "layers", graph.layers(), "joins", graph.joins(),
                "requirementHash", Hashing.sha256(String.valueOf(scenario.requirement)).substring(0, 12)));

        long deadline = System.currentTimeMillis() + cfg.maxRuntimeMs();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, cfg.parallelism()));
        CompletionService<String> cs = new ExecutorCompletionService<>(pool);
        List<Integer> pendingEvents = new ArrayList<>();
        int inFlight = 0;
        try {
            while (true) {
                if (System.currentTimeMillis() > deadline && safeStop.trip("runtime budget exceeded")) incident("runtime budget exceeded");
                if (totalAttempts.get() > cfg.maxAttemptsTotal() && safeStop.trip("attempt budget exceeded")) incident("attempt budget exceeded");
                boolean stopped = safeStop.stopped();
                if (stopped && !stopTraced) { stopTraced = true; incident(safeStop.reason()); }
                if (!stopped && pendingEvents.isEmpty()) {
                    for (NodeSpec n : graph.nodes()) {
                        if (ready(n)) {
                            synchronized (states) { states.get(n.id).status = NodeStatus.RUNNING; }
                            cs.submit(() -> { executeNode(n); return n.id; });
                            inFlight++;
                        }
                    }
                }
                if (inFlight == 0) {
                    if (!pendingEvents.isEmpty() && !stopped) {
                        for (int idx : pendingEvents) applyEvent(scenario.events.get(idx));
                        pendingEvents.clear();
                        continue;
                    }
                    break;
                }
                String done;
                try {
                    done = cs.take().get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new IllegalStateException("worker crashed", e);
                }
                inFlight--;
                persist();
                for (int i = 0; i < scenario.events.size(); i++) {
                    ScenarioSpec.EventSpec ev = scenario.events.get(i);
                    if (!firedEvents.contains(i) && ev.afterNode.equals(done) && states.get(done).status == NodeStatus.DONE) {
                        firedEvents.add(i);
                        pendingEvents.add(i);
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }

        for (var e : states.entrySet()) {
            if (!e.getValue().status.terminal()) {
                e.getValue().status = NodeStatus.SKIPPED;
                audit("NODE_SKIPPED", "orchestrator", e.getKey(), mapOf("reason", safeStop.reason() == null ? "unsatisfied dependencies" : safeStop.reason()));
            }
        }
        boolean allDone = states.values().stream().allMatch(s -> s.status == NodeStatus.DONE);
        String status = allDone ? "COMPLETED" : "HALTED";
        String reason = allDone ? null : (safeStop.reason() == null ? "unsatisfied dependencies" : safeStop.reason());
        metrics.finish(graph.nodes().size(), (int) states.values().stream().filter(x -> x.status == NodeStatus.DONE).count());
        audit("RUN_FINISHED", "orchestrator", null, mapOf("status", status, "reason", reason));
        persist();
        RunReporter.write(cfg.runDir(), scenario, graph, states, metrics.snapshot(), context, lineage, decisions,
                AuditVerifier.verify(cfg.runDir().resolve("audit.jsonl")), status, reason);
        return new RunResult(status, reason, metrics.snapshot(), new LinkedHashMap<>(states));
    }

    private volatile boolean stopTraced = false;

    private boolean ready(NodeSpec n) {
        synchronized (states) {
            if (!states.get(n.id).status.schedulable()) return false;
            for (String d : n.dependsOn) if (states.get(d).status != NodeStatus.DONE) return false;
            return true;
        }
    }

    // ---------------------------------------------------------------- node execution

    private void executeNode(NodeSpec n) {
        NodeState st = states.get(n.id);
        long t0 = System.currentTimeMillis();
        synchronized (states) {
            st.startedAtMs = t0;
            st.attempts = 0;
            st.usedFallback = false;
            st.lastError = null;
        }
        audit("NODE_STARTED", "orchestrator", n.id, mapOf("agent", n.agent, "dependsOn", n.dependsOn, "run", st.runs + 1));

        List<String> missing = n.consumes.stream().filter(a -> !context.has(a)).toList();
        if (!missing.isEmpty()) {
            failNode(n, st, "entry gate failed: missing inputs " + missing);
            return;
        }
        Map<String, Integer> consumed = new LinkedHashMap<>();
        n.consumes.forEach(a -> consumed.put(a, context.version(a)));

        int maxRetries = n.maxRetries >= 0 ? n.maxRetries : cfg.defaultMaxRetries();
        List<String> chain = n.fallbackAgent == null ? List.of(n.agent) : List.of(n.agent, n.fallbackAgent);
        String lastErr = null;
        boolean ok = false;
        outer:
        for (int phase = 0; phase < chain.size(); phase++) {
            boolean fb = phase == 1;
            if (fb) {
                metrics.fallback();
                synchronized (states) { st.usedFallback = true; }
                audit("FALLBACK_ENGAGED", "orchestrator", n.id, mapOf("from", n.agent, "to", n.fallbackAgent, "lastError", lastErr));
            }
            int budget = fb ? 1 : 1 + maxRetries;
            for (int a = 1; a <= budget; a++) {
                if (a > 1) {
                    if (totalAttempts.get() >= cfg.maxAttemptsTotal()) safeStop.trip("attempt budget exceeded");
                    if (safeStop.stopped()) {
                        abort(n, st);
                        return;
                    }
                    metrics.retry();
                    audit("RETRY", "orchestrator", n.id, mapOf("attempt", a, "of", budget, "lastError", lastErr));
                    sleep(cfg.backoffMs() * (1L << Math.min(a - 2, 6)));
                }
                Attempt r = attempt(n, chain.get(phase), fb, consumed);
                if (r.ok()) {
                    ok = true;
                    break outer;
                }
                lastErr = r.error();
                if (r.nonRetryable()) break outer;
            }
        }
        if (!ok) {
            failNode(n, st, lastErr);
            return;
        }
        long dur = System.currentTimeMillis() - t0;
        synchronized (states) {
            st.status = NodeStatus.DONE;
            st.runs++;
            st.endedAtMs = System.currentTimeMillis();
        }
        metrics.nodeDone(n.id, st.attempts, dur);
        audit("NODE_COMPLETED", "orchestrator", n.id, mapOf("attempts", st.attempts, "durationMs", dur, "usedFallback", st.usedFallback));
    }

    private Attempt attempt(NodeSpec n, String agentName, boolean fb, Map<String, Integer> consumed) {
        NodeState st = states.get(n.id);
        int attemptNo;
        synchronized (states) { attemptNo = ++st.attempts; }
        totalAttempts.incrementAndGet();
        audit("ATTEMPT_STARTED", "agent:" + agentName, n.id, mapOf("attempt", attemptNo, "fallback", fb));
        boolean applied = false;
        try {
            var fault = faults.before(n.id, fb);
            TaskContext tc = new TaskContext(n, scenario, context, cfg.targetDir(), attemptNo, fb, this::runFacts);
            AgentResult res = agents.get(agentName).execute(tc);
            res = faults.corrupt(fault, res);
            List<FileChange> changes = normalize(res.changes());

            PolicyReport pr = policy.evaluate(n, changes, cfg.targetDir());
            audit("POLICY_EVALUATED", "policy-engine", n.id, mapOf("violations", pr.violations().stream()
                    .map(v -> v.rule() + ":" + v.severity() + ":" + v.path() + ":" + v.message()).toList()));
            if (pr.blocked()) {
                policyBlocks.incrementAndGet();
                metrics.policyBlock();
                metrics.attempt(n.id, false);
                String msg = pr.violations().stream().filter(v -> v.severity() == Violation.Severity.BLOCK)
                        .map(v -> v.path() + " " + v.message()).reduce((a, b) -> a + "; " + b).orElse("");
                audit("POLICY_BLOCKED", "policy-engine", n.id, mapOf("detail", msg));
                decide("policy-block", n.id, "policy-engine", "blocked: " + msg);
                return Attempt.fatal("policy violation: " + msg);
            }

            List<FileChange> high = changes.stream().filter(c -> ChangeClassifier.tier(c) == RiskTier.HIGH).toList();
            if (n.humanGate || !high.isEmpty()) {
                Attempt denied = requestApproval(n, res, changes, high, consumed);
                if (denied != null) {
                    metrics.attempt(n.id, false);
                    return denied;
                }
            }

            snapshots.begin(n.id);
            for (FileChange c : changes) snapshots.capture(n.id, c.path());
            applied = true;
            for (FileChange c : changes) applyChange(n, c);
            audit("CHANGES_APPLIED", "orchestrator", n.id, mapOf("files", changes.stream().map(c -> c.kind() + " " + c.path()).toList()));

            for (String vname : n.exitGate) {
                Validator.Result vr = validators.get(vname).validate(new Validator.Context(cfg.targetDir(),
                        cfg.runDir().resolve("logs"), n, changes, context, cfg.skipBuild()));
                audit("VALIDATION", "validator:" + vname, n.id, mapOf("passed", vr.passed(), "executed", vr.executed(), "detail", vr.detail()));
                if (vname.equals("maven-test") && vr.passed() && vr.executed()) testsExecuted.set(true);
                if (!vr.passed()) {
                    rollbackPending(n, "exit gate " + vname + " failed");
                    metrics.attempt(n.id, false);
                    return Attempt.retryable(vname + " failed: " + vr.detail());
                }
            }

            Map<String, Object> produced = new LinkedHashMap<>();
            for (ArtifactWrite aw : res.artifacts()) {
                Artifact a = context.put(aw.id(), aw.type(), aw.content(), n.id, agentName, consumed);
                produced.put(aw.id(), "v" + a.version());
            }
            snapshots.commit(n.id);
            metrics.attempt(n.id, true);
            lineage.add(mapOf("node", n.id, "run", st.runs + 1, "agent", agentName, "fallback", fb,
                    "consumed", consumed, "produced", produced, "files", changes.size()));
            audit("ATTEMPT_SUCCEEDED", "agent:" + agentName, n.id, mapOf("summary", res.summary(), "produced", produced, "consumed", consumed));
            return Attempt.success();
        } catch (Exception e) {
            if (applied) {
                try { rollbackPending(n, "exception: " + e.getMessage()); } catch (RuntimeException ignored) { /* logged in audit */ }
            }
            metrics.attempt(n.id, false);
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            audit("ATTEMPT_FAILED", "agent:" + agentName, n.id, mapOf("error", msg));
            return Attempt.retryable(msg);
        }
    }

    private Attempt requestApproval(NodeSpec n, AgentResult res, List<FileChange> changes, List<FileChange> high,
                                    Map<String, Integer> consumed) {
        StringBuilder fp = new StringBuilder(n.id).append(consumed);
        changes.forEach(c -> fp.append(c.path()).append(Hashing.sha256(String.valueOf(c.content())), 0, 8));
        String key = Hashing.sha256(fp.toString());
        if (approvedKeys.contains(key)) return null; // identical proposal already approved (retry after validator failure)

        String reason = n.humanGate ? (n.gateReason == null ? "human gate" : n.gateReason)
                : "high-impact changes: " + high.stream().map(ChangeClassifier::reason).distinct().toList();
        StringBuilder sum = new StringBuilder(res.summary()).append('\n');
        changes.stream().limit(15).forEach(c -> sum.append("  ").append(c.kind()).append(' ').append(c.path()).append('\n'));
        if (n.humanGate && !res.artifacts().isEmpty()) {
            String body = res.artifacts().get(0).content();
            sum.append(body, 0, Math.min(1500, body.length())).append('\n');
        }
        synchronized (states) { states.get(n.id).status = NodeStatus.WAITING_APPROVAL; }
        long t = System.currentTimeMillis();
        ApprovalGate.Decision d;
        synchronized (approvalLock) {
            audit("APPROVAL_REQUESTED", "orchestrator", n.id, mapOf("reason", reason, "highImpact", high.stream().map(FileChange::path).toList()));
            d = approvals.request(new ApprovalGate.Request(n.id, reason, sum.toString(), high.stream().map(FileChange::path).toList()));
        }
        metrics.approval(d.approved(), System.currentTimeMillis() - t);
        synchronized (states) { states.get(n.id).status = NodeStatus.RUNNING; }
        audit(d.approved() ? "APPROVAL_GRANTED" : "APPROVAL_DENIED", "human:" + d.approver(), n.id, mapOf("comment", d.comment(), "reason", reason));
        decide(d.approved() ? "approval-granted" : "approval-denied", n.id, d.approver(), reason);
        if (d.approved()) {
            approvedKeys.add(key);
            return null;
        }
        return Attempt.fatal("approval denied by " + d.approver());
    }

    private List<FileChange> normalize(List<FileChange> in) {
        List<FileChange> out = new ArrayList<>();
        for (FileChange c : in) {
            if (c.kind() == ChangeKind.DELETE) { out.add(c); continue; }
            boolean exists = Files.exists(cfg.targetDir().resolve(c.path()).normalize());
            out.add(c.withKind(exists ? ChangeKind.MODIFY : ChangeKind.CREATE));
        }
        return out;
    }

    private void applyChange(NodeSpec n, FileChange c) throws IOException {
        Path f = cfg.targetDir().resolve(c.path()).normalize();
        if (c.kind() == ChangeKind.DELETE) {
            Files.deleteIfExists(f);
            return;
        }
        Files.createDirectories(f.getParent());
        String body = c.content();
        if (c.path().endsWith(".java"))
            body = "// Generated by agentic orchestrator | run " + runId + " | node " + n.id + "\n" + body;
        Files.writeString(f, body, StandardCharsets.UTF_8);
    }

    private void rollbackPending(NodeSpec n, String why) {
        try {
            snapshots.rollbackPending(n.id);
        } catch (IOException e) {
            throw new IllegalStateException("rollback failed for " + n.id, e);
        }
        synchronized (states) { states.get(n.id).rollbacks++; }
        metrics.rollback();
        audit("ROLLBACK", "orchestrator", n.id, mapOf("reason", why));
    }

    private void failNode(NodeSpec n, NodeState st, String err) {
        synchronized (states) {
            st.status = NodeStatus.FAILED;
            st.lastError = err;
            st.endedAtMs = System.currentTimeMillis();
        }
        audit("NODE_FAILED", "orchestrator", n.id, mapOf("error", err, "attempts", st.attempts, "rollbacks", st.rollbacks));
        safeStop.trip("node " + n.id + " failed: " + err); // traced once by the scheduler loop
    }

    private void abort(NodeSpec n, NodeState st) {
        synchronized (states) { st.status = NodeStatus.PENDING; }
        audit("NODE_ABORTED", "orchestrator", n.id, mapOf("reason", "safe-stop active: " + safeStop.reason()));
    }

    private void incident(String why) {
        audit("SAFE_STOP", "orchestrator", null, mapOf("reason", why, "action", "no new nodes scheduled; state preserved; resume possible"));
        decide("safe-stop", null, "orchestrator", why);
    }

    // ---------------------------------------------------------------- events / replanning

    private void applyEvent(ScenarioSpec.EventSpec ev) throws IOException {
        if (!"clarification".equals(ev.type)) throw new IllegalStateException("unknown event type " + ev.type);
        Map<String, String> current = new LinkedHashMap<>();
        for (String id : List.of("req.security", "req.performance", "req.ambiguities")) current.put(id, context.content(id));
        Map<String, String> refined = RequirementsAnalyzer.refine(current, ev.answers);
        List<String> changed = new ArrayList<>();
        for (var e : refined.entrySet()) {
            int before = context.version(e.getKey());
            Artifact a = context.put(e.getKey(), "json", e.getValue(), "human:stakeholder", "clarification", Map.of());
            if (a.version() > before) changed.add(e.getKey());
        }
        audit("EVENT_APPLIED", "human:stakeholder", ev.afterNode, mapOf("type", ev.type, "answers", ev.answers, "changedArtifacts", changed));
        decide("clarification", ev.afterNode, "stakeholder", ev.answers.toString());
        if (changed.isEmpty()) return;
        Replanner.Outcome o;
        synchronized (states) { o = replanner.replan(graph, changed, states, snapshots); }
        metrics.replan();
        o.rolledBack().forEach(n -> metrics.rollback());
        audit("REPLAN", "orchestrator", null, mapOf("changedArtifacts", changed, "invalidated", o.invalidated(),
                "rolledBack", o.rolledBack(), "retained", o.retained(), "cause", "upstream artifact change after " + ev.afterNode));
        decide("replan", null, "orchestrator", "invalidated " + o.invalidated() + ", retained " + o.retained());
    }

    // ---------------------------------------------------------------- facts, persistence, helpers

    @SuppressWarnings("unchecked")
    private Map<String, Object> runFacts() {
        Map<String, Object> f = new LinkedHashMap<>();
        List<String> notDone = new ArrayList<>();
        synchronized (states) {
            states.forEach((k, v) -> { if (v.status != NodeStatus.DONE) notDone.add(k); });
        }
        f.put("nodesNotDone", notDone);
        f.put("testsExecuted", testsExecuted.get());
        f.put("policyBlocks", policyBlocks.get());
        Map<String, Object> m = metrics.snapshot();
        f.put("retries", m.get("retries"));
        f.put("rollbacks", m.get("rollbacks"));
        f.put("replans", m.get("replans"));
        List<String> open = new ArrayList<>();
        if (context.has("req.ambiguities")) {
            for (Map<String, Object> a : (List<Map<String, Object>>) RequirementsAnalyzer.parse(context.content("req.ambiguities")).get("items"))
                if ("OPEN".equals(a.get("status"))) open.add((String) a.get("id"));
        }
        f.put("openAmbiguities", open);
        AuditVerifier.Result r;
        synchronized (audit) { r = AuditVerifier.verify(cfg.runDir().resolve("audit.jsonl")); }
        f.put("auditIntact", r.valid());
        f.put("auditDetail", r.records() + " records, " + r.detail());
        return f;
    }

    private void decide(String type, String node, String actor, String text) {
        decisions.add(mapOf("type", type, "node", node, "actor", actor, "text", text, "ts", System.currentTimeMillis()));
    }

    private void audit(String type, String actor, String node, Map<String, Object> data) {
        audit.record(type, actor, node, data);
    }

    private synchronized void persist() {
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            synchronized (states) { s.put("states", new LinkedHashMap<>(states)); }
            s.put("firedEvents", firedEvents);
            s.put("testsExecuted", testsExecuted.get());
            s.put("completionOrder", snapshots.completionOrder());
            OM.writerWithDefaultPrettyPrinter().writeValue(cfg.runDir().resolve("state.json").toFile(), s);
            context.save(cfg.runDir().resolve("context.json"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot persist state", e);
        }
    }

    private void loadState() throws IOException {
        Path f = cfg.runDir().resolve("state.json");
        if (!Files.exists(f)) return;
        Map<String, Object> s = OM.readValue(f.toFile(), new TypeReference<>() {});
        Map<String, NodeState> loaded = OM.convertValue(s.get("states"), new TypeReference<LinkedHashMap<String, NodeState>>() {});
        for (var e : loaded.entrySet()) {
            NodeState ns = e.getValue();
            if (ns.status == NodeStatus.RUNNING || ns.status == NodeStatus.WAITING_APPROVAL || ns.status == NodeStatus.SKIPPED
                    || ns.status == NodeStatus.FAILED) {
                ns.status = NodeStatus.PENDING;
                ns.attempts = 0;
            }
            if (states.containsKey(e.getKey())) states.put(e.getKey(), ns);
        }
        ((List<?>) s.get("firedEvents")).forEach(i -> firedEvents.add(((Number) i).intValue()));
        testsExecuted.set(Boolean.TRUE.equals(s.get("testsExecuted")));
        context.load(cfg.runDir().resolve("context.json"));
        List<String> order = OM.convertValue(s.get("completionOrder"), new TypeReference<List<String>>() {});
        snapshots.loadCommitted(order);
        Files.deleteIfExists(cfg.runDir().resolve("STOP"));
        audit("RUN_RESUMED", "orchestrator", null, mapOf("doneNodes", states.entrySet().stream()
                .filter(e -> e.getValue().status == NodeStatus.DONE).map(Map.Entry::getKey).toList()));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}

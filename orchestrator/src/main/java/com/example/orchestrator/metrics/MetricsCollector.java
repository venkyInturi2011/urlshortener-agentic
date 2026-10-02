package com.example.orchestrator.metrics;

import java.util.*;

/** Reliability metrics: success rate, retries, rollbacks, MTTR, end-to-end and per-node latency. */
public final class MetricsCollector {
    private long runStart = System.currentTimeMillis();
    private long runEnd;
    private int attempts, failedAttempts, retries, fallbacks, rollbacks, policyBlocks, approvalsRequested,
            approvalsDenied, replans, nodesDone, nodesTotal, firstTryDone;
    private long approvalWaitMs;
    private final Map<String, Long> firstFailureAt = new HashMap<>();
    private final List<Long> recoveryMs = new ArrayList<>();
    private final Map<String, Long> nodeMs = new LinkedHashMap<>();

    public synchronized void runStarted() { runStart = System.currentTimeMillis(); }

    public synchronized void attempt(String node, boolean success) {
        attempts++;
        if (!success) {
            failedAttempts++;
            firstFailureAt.putIfAbsent(node, System.currentTimeMillis());
        }
    }

    public synchronized void retry() { retries++; }

    public synchronized void fallback() { fallbacks++; }

    public synchronized void rollback() { rollbacks++; }

    public synchronized void policyBlock() { policyBlocks++; }

    public synchronized void replan() { replans++; }

    public synchronized void approval(boolean approved, long waitMs) {
        approvalsRequested++;
        if (!approved) approvalsDenied++;
        approvalWaitMs += waitMs;
    }

    public synchronized void nodeDone(String node, int attemptsUsed, long ms) {
        nodesDone++;
        if (attemptsUsed == 1) firstTryDone++;
        nodeMs.put(node, ms);
        Long f = firstFailureAt.remove(node);
        if (f != null) recoveryMs.add(System.currentTimeMillis() - f);
    }

    public synchronized void finish(int totalNodes) {
        runEnd = System.currentTimeMillis();
        nodesTotal = totalNodes;
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long end = runEnd == 0 ? System.currentTimeMillis() : runEnd;
        m.put("nodes_total", nodesTotal);
        m.put("nodes_done", nodesDone);
        m.put("node_success_rate", nodesTotal == 0 ? 0.0 : round((double) Math.min(nodesDone, nodesTotal) / nodesTotal));
        m.put("first_attempt_success_rate", nodesDone == 0 ? 0.0 : round((double) firstTryDone / nodesDone));
        m.put("attempts", attempts);
        m.put("failed_attempts", failedAttempts);
        m.put("retries", retries);
        m.put("fallbacks", fallbacks);
        m.put("rollbacks", rollbacks);
        m.put("policy_blocks", policyBlocks);
        m.put("approvals_requested", approvalsRequested);
        m.put("approvals_denied", approvalsDenied);
        m.put("approval_wait_ms", approvalWaitMs);
        m.put("replans", replans);
        m.put("mttr_ms", recoveryMs.isEmpty() ? null : (long) recoveryMs.stream().mapToLong(Long::longValue).average().orElse(0));
        m.put("recovered_incidents", recoveryMs.size());
        m.put("unrecovered_incidents", firstFailureAt.size());
        m.put("e2e_latency_ms", end - runStart);
        m.put("node_latency_ms", new LinkedHashMap<>(nodeMs));
        return m;
    }

    private static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}

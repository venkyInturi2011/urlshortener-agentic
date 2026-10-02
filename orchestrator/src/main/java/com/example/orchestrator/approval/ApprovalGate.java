package com.example.orchestrator.approval;

import java.util.List;

/** Human-in-the-loop checkpoint. Implementations may block (console) or be scripted (CI / demos). */
public interface ApprovalGate {
    record Request(String nodeId, String reason, String summary, List<String> highImpactPaths) {}

    record Decision(boolean approved, String approver, String comment) {}

    Decision request(Request request);
}

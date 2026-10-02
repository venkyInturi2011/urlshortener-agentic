package com.example.orchestrator.approval;

/** Approves everything. Only for demos/tests: the audit log still records who "approved". */
public final class AutoApproveGate implements ApprovalGate {
    @Override
    public Decision request(Request r) {
        return new Decision(true, "auto-approver", "approved by --approve=auto");
    }
}

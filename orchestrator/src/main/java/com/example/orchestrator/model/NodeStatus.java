package com.example.orchestrator.model;

/** Lifecycle of a workflow node. INVALIDATED nodes are re-schedulable like PENDING ones. */
public enum NodeStatus {
    PENDING, RUNNING, WAITING_APPROVAL, DONE, FAILED, SKIPPED, INVALIDATED;

    public boolean schedulable() {
        return this == PENDING || this == INVALIDATED;
    }

    public boolean terminal() {
        return this == DONE || this == FAILED || this == SKIPPED;
    }
}

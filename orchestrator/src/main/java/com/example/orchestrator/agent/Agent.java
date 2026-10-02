package com.example.orchestrator.agent;

import com.example.orchestrator.model.AgentResult;

/**
 * An agent performs one bounded unit of SDLC work and returns proposals (artifacts + file changes).
 * It never writes to the target itself: governance (policy, approval, snapshot, validation) lives in the engine.
 * Implementations may be rule-based (as here) or LLM-backed; the contract is identical.
 */
public interface Agent {
    String name();

    AgentResult execute(TaskContext ctx) throws Exception;
}

package com.example.orchestrator.model;

import java.util.Map;

/** Immutable, versioned, content-hashed unit of cross-stage context. */
public record Artifact(String id, int version, String type, String sha256, String content,
                       String producedBy, String agent, Map<String, Integer> consumed, long createdAtMs) {}

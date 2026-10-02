package com.example.orchestrator.policy;

import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.List;

public interface PolicyRule {
    String id();

    List<Violation> check(NodeSpec node, List<FileChange> changes, Path targetDir);
}

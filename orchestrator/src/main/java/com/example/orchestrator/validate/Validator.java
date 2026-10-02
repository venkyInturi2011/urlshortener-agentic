package com.example.orchestrator.validate;

import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;

import java.nio.file.Path;
import java.util.List;

/** Exit-gate check run after a node's changes are applied. A failure triggers rollback and retry. */
public interface Validator {
    record Context(Path targetDir, Path logDir, NodeSpec node, List<FileChange> changes, ContextStore context,
                   boolean skipBuild) {}

    record Result(boolean passed, boolean executed, String detail) {
        public static Result pass(String d) { return new Result(true, true, d); }
        public static Result fail(String d) { return new Result(false, true, d); }
        public static Result skipped(String d) { return new Result(true, false, d); }
    }

    String name();

    Result validate(Context ctx);
}

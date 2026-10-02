package com.example.orchestrator.validate;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Documentation exit gate: README and CHANGELOG exist after the node's changes were applied. */
public final class DocsPresentValidator implements Validator {
    @Override
    public String name() { return "docs-present"; }

    @Override
    public Result validate(Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String f : List.of("README.md", "CHANGELOG.md"))
            if (!Files.exists(ctx.targetDir().resolve(f))) missing.add(f);
        return missing.isEmpty() ? Result.pass("README.md and CHANGELOG.md present") : Result.fail("missing " + missing);
    }
}

package com.example.orchestrator.validate;

import com.example.orchestrator.model.FileChange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

/** Design exit gate: the proposed OpenAPI document parses and declares paths; architecture doc is present. */
public final class DesignDocsValidator implements Validator {
    @Override
    public String name() { return "design-docs"; }

    @Override
    public Result validate(Context ctx) {
        String openapi = null;
        boolean arch = false;
        for (FileChange c : ctx.changes()) {
            if (c.path().equals("docs/design/openapi.yaml")) openapi = c.content();
            if (c.path().equals("docs/design/architecture.md")) arch = c.content() != null && c.content().contains("## Components");
        }
        if (openapi == null || !arch) return Result.fail("design documents missing (openapi.yaml / architecture.md with Components)");
        try {
            JsonNode api = new YAMLMapper().readTree(openapi);
            if (!api.has("openapi") || !api.path("paths").fields().hasNext())
                return Result.fail("OpenAPI document lacks 'openapi' version or 'paths'");
            return Result.pass("OpenAPI valid with " + api.path("paths").size() + " paths");
        } catch (Exception e) {
            return Result.fail("OpenAPI not parseable: " + e.getMessage());
        }
    }
}

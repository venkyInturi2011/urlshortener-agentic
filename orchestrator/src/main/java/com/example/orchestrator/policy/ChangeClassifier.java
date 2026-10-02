package com.example.orchestrator.policy;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.RiskTier;

/**
 * Engine-side impact classification. Agents cannot self-declare a change as low risk: deletes, dependency
 * changes, schema migrations and modifications of the public API contract are HIGH and need human approval.
 */
public final class ChangeClassifier {
    private ChangeClassifier() {}

    public static RiskTier tier(FileChange c) {
        String p = c.path().replace('\\', '/');
        if (c.kind() == ChangeKind.DELETE) return RiskTier.HIGH;
        if (c.kind() == ChangeKind.MODIFY) {
            if (p.equals("pom.xml")) return RiskTier.HIGH;
            if (p.endsWith(".sql")) return RiskTier.HIGH;
            if (p.contains("/dto/") || p.contains("/api/")) return RiskTier.HIGH;
        }
        return RiskTier.LOW;
    }

    public static String reason(FileChange c) {
        String p = c.path().replace('\\', '/');
        if (c.kind() == ChangeKind.DELETE) return "file deletion";
        if (p.equals("pom.xml")) return "dependency/build change";
        if (p.endsWith(".sql")) return "schema migration";
        return "public API contract change";
    }
}

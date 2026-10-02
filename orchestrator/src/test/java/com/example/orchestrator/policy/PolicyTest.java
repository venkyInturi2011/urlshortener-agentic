package com.example.orchestrator.policy;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;
import com.example.orchestrator.model.RiskTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyTest {

    @TempDir Path tmp;
    private final PolicyEngine policy = PolicyEngine.defaults();
    private final NodeSpec node = new NodeSpec();

    private PolicyReport eval(FileChange... changes) {
        return policy.evaluate(node, List.of(changes), tmp);
    }

    private static FileChange java(String path, String body) {
        return new FileChange(path, ChangeKind.CREATE, "package a;\nclass X {\n" + body + "\n}\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "String password = \"SuperSecret123\";",
            "String apiKey = \"abcdef123456\";",
            "Runtime.getRuntime().exec(\"rm -rf /\");",
            "new ProcessBuilder(\"sh\");",
            "Object o = new ObjectInputStream(in).readObject();",
            "jdbc.query(\"SELECT * FROM t WHERE a=\" + x, mapper);"})
    void securityRuleBlocksDangerousProductionCode(String body) {
        PolicyReport r = eval(java("src/main/java/a/X.java", body));
        assertThat(r.blocked()).as(body).isTrue();
    }

    @Test
    void securityFindingsInTestsAreOnlyWarnings() {
        PolicyReport r = eval(java("src/test/java/a/XTest.java", "String password = \"SuperSecret123\";"));
        assertThat(r.blocked()).isFalse();
        assertThat(r.warnings()).hasSize(1);
    }

    @Test
    void placeholdersAndEnvReferencesAreNotSecrets() {
        FileChange yml = new FileChange("src/main/resources/application.yml", ChangeKind.CREATE,
                "shortener:\n  admin-key: ${SHORTENER_ADMIN_KEY:}\nspring:\n  datasource:\n    password: \"\"\n");
        assertThat(eval(yml).violations()).isEmpty();
    }

    @Test
    void complianceRuleBlocksPiiLoggingAndCopyleft() {
        assertThat(eval(java("src/main/java/a/X.java", "log.info(\"user {}\", email);")).blocked()).isTrue();
        FileChange lic = new FileChange("README.md", ChangeKind.CREATE, "Licensed under the GNU General Public License");
        assertThat(eval(lic).blocked()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "/etc/passwd", "C:/Windows/x.txt", "a/../../b.txt", ".git/config", ".github/workflows/x.yml"})
    void changeControlBlocksPathEscapesAndProtectedPaths(String path) {
        assertThat(eval(new FileChange(path, ChangeKind.CREATE, "x")).blocked()).as(path).isTrue();
    }

    @Test
    void changeControlBlocksDisallowedTypesOversizeAndTooManyFiles() {
        assertThat(eval(new FileChange("bin/tool.exe", ChangeKind.CREATE, "x")).blocked()).isTrue();
        assertThat(eval(new FileChange("docs/big.md", ChangeKind.CREATE, "x".repeat(200_001))).blocked()).isTrue();
        FileChange[] many = new FileChange[41];
        for (int i = 0; i < many.length; i++) many[i] = new FileChange("docs/f" + i + ".md", ChangeKind.CREATE, "x");
        assertThat(eval(many).blocked()).isTrue();
    }

    @Test
    void changeControlEnforcesDependencyAllowList() {
        String ok = "<project><dependencies><dependency><groupId>org.springframework.boot</groupId></dependency></dependencies></project>";
        String bad = "<project><dependencies><dependency><groupId>com.evil</groupId></dependency></dependencies></project>";
        assertThat(eval(new FileChange("pom.xml", ChangeKind.CREATE, ok)).blocked()).isFalse();
        PolicyReport r = eval(new FileChange("pom.xml", ChangeKind.CREATE, bad));
        assertThat(r.blocked()).isTrue();
        assertThat(r.violations().get(0).message()).contains("com.evil");
    }

    @Test
    void deletesAreAllowedByPolicyButClassifiedHigh() {
        FileChange del = new FileChange("src/main/java/a/X.java", ChangeKind.DELETE, null);
        assertThat(eval(del).blocked()).isFalse();
        assertThat(ChangeClassifier.tier(del)).isEqualTo(RiskTier.HIGH);
    }

    @Test
    void classifierSeparatesLowAndHighImpact() {
        assertThat(ChangeClassifier.tier(new FileChange("src/main/java/a/service/S.java", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.LOW);
        assertThat(ChangeClassifier.tier(new FileChange("pom.xml", ChangeKind.CREATE, "x"))).isEqualTo(RiskTier.LOW);
        assertThat(ChangeClassifier.tier(new FileChange("pom.xml", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.HIGH);
        assertThat(ChangeClassifier.tier(new FileChange("src/main/resources/schema.sql", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.HIGH);
        assertThat(ChangeClassifier.tier(new FileChange("src/main/java/a/dto/D.java", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.HIGH);
        assertThat(ChangeClassifier.tier(new FileChange("src/main/java/a/api/C.java", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.HIGH);
        assertThat(ChangeClassifier.tier(new FileChange("src/test/java/a/api/CTest.java", ChangeKind.MODIFY, "x"))).isEqualTo(RiskTier.LOW);
        assertThat(ChangeClassifier.reason(new FileChange("pom.xml", ChangeKind.MODIFY, "x"))).contains("dependency");
        assertThat(ChangeClassifier.reason(new FileChange("a.sql", ChangeKind.MODIFY, "x"))).contains("schema");
        assertThat(ChangeClassifier.reason(new FileChange("a/api/C.java", ChangeKind.MODIFY, "x"))).contains("API");
        assertThat(ChangeClassifier.reason(new FileChange("a", ChangeKind.DELETE, null))).contains("deletion");
    }
}

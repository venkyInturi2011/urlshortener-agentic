package com.example.orchestrator.validate;

import com.example.orchestrator.context.ContextStore;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.NodeSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatorsTest {

    @TempDir Path tmp;

    private Validator.Context ctx(boolean skipBuild, FileChange... changes) {
        NodeSpec n = new NodeSpec();
        n.id = "n";
        return new Validator.Context(tmp, tmp.resolve("logs"), n, List.of(changes), new ContextStore(), skipBuild);
    }

    private static FileChange f(String path, String content) {
        return new FileChange(path, ChangeKind.CREATE, content);
    }

    private final StructureValidator structure = new StructureValidator();

    @Test
    void wellFormedFilesPass() {
        Validator.Result r = structure.validate(ctx(false,
                f("A.java", "package a;\npublic class A { void m() { if (true) { } } }"),
                f("pom.xml", "<project><a/></project>"),
                f("c.yml", "a:\n  b: 1\n"),
                f("d.json", "{\"a\":1}"),
                f("e.sql", "SELECT 1;")));
        assertThat(r.passed()).as(r.detail()).isTrue();
    }

    @Test
    void bracesInStringsCommentsAndTextBlocksDoNotConfuseTheChecker() {
        String src = "package a;\n// } stray in comment\n/* { */\npublic class A {\n String s = \"}}}\";\n char c = '{';\n"
                + " String t = \"\"\"\n   { not code\n   \"\"\";\n}\n";
        assertThat(structure.validate(ctx(false, f("A.java", src))).passed()).isTrue();
    }

    @Test
    void detectsBrokenJava() {
        assertThat(structure.validate(ctx(false, f("A.java", "package a;\nclass A {"))).detail()).contains("unbalanced braces");
        assertThat(structure.validate(ctx(false, f("A.java", "package a;\nclass A { void m( { } }"))).passed()).isFalse();
        assertThat(structure.validate(ctx(false, f("A.java", "class A { }"))).detail()).contains("missing package");
        assertThat(structure.validate(ctx(false, f("A.java", "package a;\n// nothing"))).detail()).contains("no type declaration");
    }

    @Test
    void detectsEmptyAndMalformedNonJavaFiles() {
        assertThat(structure.validate(ctx(false, f("a.txt", " "))).detail()).contains("empty");
        assertThat(structure.validate(ctx(false, f("p.xml", "<a>"))).passed()).isFalse();
        assertThat(structure.validate(ctx(false, f("p.xml", "<!DOCTYPE a [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a>&x;</a>"))).passed()).isFalse();
        assertThat(structure.validate(ctx(false, f("d.json", "{"))).passed()).isFalse();
        assertThat(structure.validate(ctx(false, f("c.yml", "a: [1, 2"))).passed()).isFalse();
    }

    @Test
    void deletesAreIgnored() {
        assertThat(structure.validate(ctx(false, new FileChange("x.java", ChangeKind.DELETE, null))).passed()).isTrue();
    }

    @Test
    void mavenValidatorIsSkippedAndReportedAsNotExecutedWhenBuildSkipped() {
        Validator.Result r = new MavenValidator("maven-test", "test", 1).validate(ctx(true));
        assertThat(r.passed()).isTrue();
        assertThat(r.executed()).isFalse();
        assertThat(r.detail()).contains("SKIPPED");
    }

    @Test
    void mavenValidatorFailsWhenNoProjectExists() {
        Validator.Result r = new MavenValidator("maven-compile", "compile", 2).validate(ctx(false));
        assertThat(r.passed()).isFalse();
        assertThat(r.executed()).isTrue();
    }

    @Test
    void designDocsValidatorChecksOpenApiAndArchitecture() {
        DesignDocsValidator v = new DesignDocsValidator();
        String arch = "# Architecture\n## Components\n";
        assertThat(v.validate(ctx(false, f("docs/design/architecture.md", arch),
                f("docs/design/openapi.yaml", "openapi: 3.0.3\npaths:\n  /x: {}\n"))).passed()).isTrue();
        assertThat(v.validate(ctx(false, f("docs/design/architecture.md", arch),
                f("docs/design/openapi.yaml", "openapi: 3.0.3\npaths: {}\n"))).passed()).isFalse();
        assertThat(v.validate(ctx(false, f("docs/design/architecture.md", arch))).detail()).contains("missing");
        assertThat(v.validate(ctx(false, f("docs/design/architecture.md", arch),
                f("docs/design/openapi.yaml", "a: [1"))).passed()).isFalse();
    }

    @Test
    void docsPresentValidatorChecksFiles() throws IOException {
        DocsPresentValidator v = new DocsPresentValidator();
        assertThat(v.validate(ctx(false)).detail()).contains("README.md").contains("CHANGELOG.md");
        Files.writeString(tmp.resolve("README.md"), "x");
        Files.writeString(tmp.resolve("CHANGELOG.md"), "x");
        assertThat(v.validate(ctx(false)).passed()).isTrue();
    }

    @Test
    void registryResolvesByNameAndRejectsUnknown() {
        ValidatorRegistry reg = ValidatorRegistry.defaults();
        assertThat(reg.get("structure")).isInstanceOf(StructureValidator.class);
        assertThatThrownBy(() -> reg.get("nope")).hasMessageContaining("unknown validator");
        assertThat(Map.of("x", 1)).isNotEmpty();
    }
}

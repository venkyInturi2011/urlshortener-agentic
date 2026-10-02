package com.example.orchestrator.validate;

import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fast static well-formedness checks: Java brace balance, XML/YAML/JSON parseability, non-empty SQL. */
public final class StructureValidator implements Validator {
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(class|interface|record|enum)\\s+\\w+");

    @Override
    public String name() { return "structure"; }

    @Override
    public Result validate(Context ctx) {
        List<String> problems = new ArrayList<>();
        for (FileChange c : ctx.changes()) {
            if (c.kind() == ChangeKind.DELETE) continue;
            String p = c.path(), s = c.content();
            if (s == null || s.isBlank()) { problems.add(p + ": empty"); continue; }
            try {
                if (p.endsWith(".java")) checkJava(p, s, problems);
                else if (p.endsWith(".xml")) parseXml(s);
                else if (p.endsWith(".yml") || p.endsWith(".yaml")) new YAMLMapper().readTree(s);
                else if (p.endsWith(".json")) new ObjectMapper().readTree(s);
            } catch (Exception e) {
                problems.add(p + ": " + e.getMessage().split("\n")[0]);
            }
        }
        return problems.isEmpty() ? Result.pass(ctx.changes().size() + " files well-formed")
                : Result.fail(String.join("; ", problems));
    }

    private static void parseXml(String s) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.newDocumentBuilder().parse(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)));
    }

    private static void checkJava(String p, String src, List<String> problems) {
        String code = strip(src);
        int braces = 0, parens = 0;
        for (char ch : code.toCharArray()) {
            if (ch == '{') braces++;
            else if (ch == '}') braces--;
            else if (ch == '(') parens++;
            else if (ch == ')') parens--;
            if (braces < 0 || parens < 0) break;
        }
        if (braces != 0) problems.add(p + ": unbalanced braces");
        if (parens != 0) problems.add(p + ": unbalanced parentheses");
        if (!code.contains("package ")) problems.add(p + ": missing package declaration");
        if (!TYPE_DECL.matcher(code).find()) problems.add(p + ": no type declaration");
    }

    /** Remove comments, string/char/text-block literals so counting is not fooled by them. */
    static String strip(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) { while (i < n && s.charAt(i) != '\n') i++; }
            else if (s.startsWith("/*", i)) { int e = s.indexOf("*/", i + 2); i = e < 0 ? n : e + 2; }
            else if (s.startsWith("\"\"\"", i)) { int e = s.indexOf("\"\"\"", i + 3); i = e < 0 ? n : e + 3; }
            else if (c == '"' || c == '\'') {
                i++;
                while (i < n && s.charAt(i) != c) { if (s.charAt(i) == '\\') i++; i++; }
                i++;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }
}

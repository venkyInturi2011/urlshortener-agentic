package com.example.shortener.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGeneratorTest {

    private final CodeGenerator generator = new CodeGenerator();

    @Test
    void generatesFixedLengthBase62Codes() {
        for (int i = 0; i < 500; i++) {
            assertThat(generator.next()).hasSize(CodeGenerator.LENGTH).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    void codesAreNotRepeatedInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) seen.add(generator.next());
        assertThat(seen).hasSize(2000);
    }
}

package com.example.orchestrator.validate;

import java.util.HashMap;
import java.util.Map;

public final class ValidatorRegistry {
    private final Map<String, Validator> byName = new HashMap<>();

    public ValidatorRegistry register(Validator v) {
        byName.put(v.name(), v);
        return this;
    }

    public Validator get(String name) {
        Validator v = byName.get(name);
        if (v == null) throw new IllegalArgumentException("unknown validator: " + name);
        return v;
    }

    public static ValidatorRegistry defaults() {
        return new ValidatorRegistry()
                .register(new StructureValidator())
                .register(new MavenValidator("maven-compile", "compile", 15))
                .register(new MavenValidator("maven-test", "test", 20))
                .register(new DesignDocsValidator())
                .register(new DocsPresentValidator());
    }
}

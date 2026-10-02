package com.example.orchestrator.context;

import com.example.orchestrator.model.Artifact;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Cross-stage context: versioned artifacts. A put with unchanged content does not bump the version. */
public final class ContextStore {
    private final Map<String, List<Artifact>> store = new LinkedHashMap<>();
    private final ObjectMapper om = new ObjectMapper();

    public synchronized Artifact put(String id, String type, String content, String producedBy, String agent,
                                     Map<String, Integer> consumed) {
        List<Artifact> hist = store.computeIfAbsent(id, k -> new ArrayList<>());
        String hash = Hashing.sha256(content);
        if (!hist.isEmpty() && hist.get(hist.size() - 1).sha256().equals(hash)) return hist.get(hist.size() - 1);
        Artifact a = new Artifact(id, hist.size() + 1, type, hash, content, producedBy, agent,
                new LinkedHashMap<>(consumed), System.currentTimeMillis());
        hist.add(a);
        return a;
    }

    public synchronized Optional<Artifact> latest(String id) {
        List<Artifact> h = store.get(id);
        return h == null || h.isEmpty() ? Optional.empty() : Optional.of(h.get(h.size() - 1));
    }

    public synchronized boolean has(String id) { return latest(id).isPresent(); }

    public synchronized String content(String id) {
        return latest(id).orElseThrow(() -> new NoSuchElementException("missing artifact " + id)).content();
    }

    public synchronized int version(String id) { return latest(id).map(Artifact::version).orElse(0); }

    public synchronized List<Artifact> history(String id) {
        return List.copyOf(store.getOrDefault(id, List.of()));
    }

    public synchronized Set<String> ids() { return new LinkedHashSet<>(store.keySet()); }

    public synchronized void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), store);
    }

    public synchronized void load(Path file) throws IOException {
        if (!Files.exists(file)) return;
        store.clear();
        store.putAll(om.readValue(file.toFile(), new TypeReference<LinkedHashMap<String, List<Artifact>>>() {}));
    }
}

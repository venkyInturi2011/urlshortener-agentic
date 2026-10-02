package com.example.orchestrator.resilience;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * File snapshots enabling rollback. Pending snapshots belong to an in-flight node attempt; committed ones are
 * kept (and persisted) so a replan can later undo a completed node, in reverse completion order.
 */
public final class SnapshotStore {
    public record Original(boolean existed, String content) {}

    private final Path target;
    private final Path dir;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, Map<String, Original>> pending = new HashMap<>();
    private final Map<String, Map<String, Original>> committed = new LinkedHashMap<>();

    public SnapshotStore(Path target, Path snapshotDir) {
        this.target = target;
        this.dir = snapshotDir;
    }

    public synchronized void begin(String node) { pending.put(node, new LinkedHashMap<>()); }

    /** Record the original state of a path before it is changed (first capture wins). */
    public synchronized void capture(String node, String relPath) throws IOException {
        Map<String, Original> snap = pending.computeIfAbsent(node, k -> new LinkedHashMap<>());
        if (snap.containsKey(relPath)) return;
        Path f = target.resolve(relPath);
        snap.put(relPath, Files.exists(f) ? new Original(true, Files.readString(f, StandardCharsets.UTF_8))
                : new Original(false, null));
    }

    public synchronized void rollbackPending(String node) throws IOException {
        restore(pending.remove(node));
    }

    public synchronized void commit(String node) throws IOException {
        Map<String, Original> snap = pending.remove(node);
        if (snap == null) snap = new LinkedHashMap<>();
        committed.remove(node); // re-run replaces older entry and moves to the end of completion order
        committed.put(node, snap);
        Files.createDirectories(dir);
        om.writeValue(dir.resolve(node + ".json").toFile(), snap);
    }

    public synchronized boolean hasCommitted(String node) { return committed.containsKey(node); }

    public synchronized void rollbackCommitted(String node) throws IOException {
        restore(committed.remove(node));
        Files.deleteIfExists(dir.resolve(node + ".json"));
    }

    /** Committed nodes in completion order (oldest first). */
    public synchronized List<String> completionOrder() { return new ArrayList<>(committed.keySet()); }

    public synchronized void loadCommitted(List<String> order) throws IOException {
        for (String node : order) {
            Path f = dir.resolve(node + ".json");
            if (Files.exists(f))
                committed.put(node, om.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Original>>() {}));
        }
    }

    private void restore(Map<String, Original> snap) throws IOException {
        if (snap == null) return;
        List<String> paths = new ArrayList<>(snap.keySet());
        Collections.reverse(paths);
        for (String p : paths) {
            Original o = snap.get(p);
            Path f = target.resolve(p);
            if (o.existed()) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, o.content(), StandardCharsets.UTF_8);
            } else {
                Files.deleteIfExists(f);
            }
        }
    }
}

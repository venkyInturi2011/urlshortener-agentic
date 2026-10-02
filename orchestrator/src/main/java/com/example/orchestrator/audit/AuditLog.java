package com.example.orchestrator.audit;

import com.example.orchestrator.context.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only, hash-chained JSONL audit log: each record commits to the previous record's hash. */
public final class AuditLog {
    public static final String GENESIS = "GENESIS";
    private static final ObjectMapper OM = new ObjectMapper();

    private final Path file;
    private final String traceId;
    private String prevHash = GENESIS;
    private long seq = 0;

    public AuditLog(Path file, String traceId) {
        this.file = file;
        this.traceId = traceId;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    Map<?, ?> last = OM.readValue(lines.get(lines.size() - 1), Map.class);
                    prevHash = (String) last.get("hash");
                    seq = ((Number) last.get("seq")).longValue();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot open audit log", e);
        }
    }

    public String traceId() { return traceId; }

    public synchronized void record(String type, String actor, String node, Map<String, Object> data) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("seq", ++seq);
        ev.put("ts", Instant.now().toString());
        ev.put("traceId", traceId);
        ev.put("type", type);
        ev.put("actor", actor);
        ev.put("node", node);
        ev.put("data", data == null ? Map.of() : data);
        ev.put("prevHash", prevHash);
        try {
            String hash = Hashing.sha256(OM.writeValueAsString(ev));
            ev.put("hash", hash);
            Files.writeString(file, OM.writeValueAsString(ev) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            prevHash = hash;
        } catch (IOException e) {
            throw new IllegalStateException("audit write failed", e);
        }
    }
}

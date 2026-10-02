package com.example.orchestrator.audit;

import com.example.orchestrator.context.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

/** Recomputes the hash chain; any edit, deletion or reordering is detected. */
public final class AuditVerifier {
    public record Result(boolean valid, long records, String detail) {}

    private static final ObjectMapper OM = new ObjectMapper();

    private AuditVerifier() {}

    @SuppressWarnings("unchecked")
    public static Result verify(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String prev = AuditLog.GENESIS;
            long expectedSeq = 0;
            for (String line : lines) {
                LinkedHashMap<String, Object> ev = OM.readValue(line, LinkedHashMap.class);
                String hash = (String) ev.remove("hash");
                if (((Number) ev.get("seq")).longValue() != ++expectedSeq)
                    return new Result(false, expectedSeq - 1, "sequence gap at " + expectedSeq);
                if (!prev.equals(ev.get("prevHash")))
                    return new Result(false, expectedSeq - 1, "broken chain at seq " + expectedSeq);
                if (!Hashing.sha256(OM.writeValueAsString(ev)).equals(hash))
                    return new Result(false, expectedSeq - 1, "hash mismatch at seq " + expectedSeq);
                prev = hash;
            }
            return new Result(true, expectedSeq, "chain intact");
        } catch (IOException | RuntimeException e) {
            return new Result(false, 0, "unreadable audit log: " + e.getMessage());
        }
    }
}

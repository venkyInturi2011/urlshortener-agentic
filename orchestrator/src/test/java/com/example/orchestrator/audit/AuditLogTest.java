package com.example.orchestrator.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditLogTest {

    @TempDir Path tmp;

    private AuditLog threeEvents(Path file) {
        AuditLog log = new AuditLog(file, "trace-1");
        log.record("A", "orchestrator", "n1", Map.of("k", "v1"));
        log.record("B", "human:alice", "n1", Map.of("k", "v2"));
        log.record("C", "policy-engine", null, Map.of());
        return log;
    }

    @Test
    void intactChainVerifies() {
        Path f = tmp.resolve("audit.jsonl");
        threeEvents(f);

        AuditVerifier.Result r = AuditVerifier.verify(f);

        assertThat(r.valid()).isTrue();
        assertThat(r.records()).isEqualTo(3);
    }

    @Test
    void editedRecordIsDetected() throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        threeEvents(f);
        Files.writeString(f, Files.readString(f).replace("human:alice", "human:mallory"));

        AuditVerifier.Result r = AuditVerifier.verify(f);

        assertThat(r.valid()).isFalse();
        assertThat(r.detail()).contains("hash mismatch");
    }

    @Test
    void deletedRecordIsDetected() throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        threeEvents(f);
        List<String> lines = new ArrayList<>(Files.readAllLines(f));
        lines.remove(1);
        Files.write(f, lines);

        assertThat(AuditVerifier.verify(f).valid()).isFalse();
    }

    @Test
    void reorderedRecordsAreDetected() throws IOException {
        Path f = tmp.resolve("audit.jsonl");
        threeEvents(f);
        List<String> lines = new ArrayList<>(Files.readAllLines(f));
        String first = lines.remove(0);
        lines.add(first);
        Files.write(f, lines);

        assertThat(AuditVerifier.verify(f).valid()).isFalse();
    }

    @Test
    void reopeningContinuesTheChain() {
        Path f = tmp.resolve("audit.jsonl");
        threeEvents(f);
        AuditLog again = new AuditLog(f, "trace-1");
        again.record("D", "orchestrator", null, Map.of());

        AuditVerifier.Result r = AuditVerifier.verify(f);

        assertThat(r.valid()).isTrue();
        assertThat(r.records()).isEqualTo(4);
        assertThat(again.traceId()).isEqualTo("trace-1");
    }

    @Test
    void unreadableLogIsReportedInvalid() {
        assertThat(AuditVerifier.verify(tmp.resolve("missing.jsonl")).valid()).isFalse();
    }

    @Test
    void concurrentWritersKeepTheChainIntact() throws Exception {
        Path f = tmp.resolve("audit.jsonl");
        AuditLog log = new AuditLog(f, "t");
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 25; i++) log.record("E", "w", "n", Map.of("i", i));
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) th.join();

        AuditVerifier.Result r = AuditVerifier.verify(f);
        assertThat(r.valid()).isTrue();
        assertThat(r.records()).isEqualTo(200);
    }
}

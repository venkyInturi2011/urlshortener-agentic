package com.example.orchestrator.resilience;

import com.example.orchestrator.model.AgentResult;
import com.example.orchestrator.model.ChangeKind;
import com.example.orchestrator.model.FileChange;
import com.example.orchestrator.model.ScenarioSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilienceTest {

    @TempDir Path tmp;

    // ---- SnapshotStore

    @Test
    void pendingRollbackRestoresModifiedAndRemovesCreatedFiles() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("t"));
        Files.writeString(target.resolve("old.txt"), "original");
        SnapshotStore s = new SnapshotStore(target, tmp.resolve("snaps"));
        s.begin("n");
        s.capture("n", "old.txt");
        s.capture("n", "new.txt");
        s.capture("n", "old.txt"); // second capture must not overwrite the original
        Files.writeString(target.resolve("old.txt"), "changed");
        Files.writeString(target.resolve("new.txt"), "created");

        s.rollbackPending("n");

        assertThat(Files.readString(target.resolve("old.txt"))).isEqualTo("original");
        assertThat(target.resolve("new.txt")).doesNotExist();
    }

    @Test
    void committedSnapshotsRollBackInReverseOrderAndPersist() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("t"));
        SnapshotStore s = new SnapshotStore(target, tmp.resolve("snaps"));
        s.begin("a");
        s.capture("a", "f.txt");
        Files.writeString(target.resolve("f.txt"), "from-a");
        s.commit("a");
        s.begin("b");
        s.capture("b", "f.txt");
        Files.writeString(target.resolve("f.txt"), "from-b");
        s.commit("b");

        assertThat(s.completionOrder()).containsExactly("a", "b");
        assertThat(s.hasCommitted("a")).isTrue();
        assertThat(tmp.resolve("snaps/a.json")).exists();

        s.rollbackCommitted("b");
        assertThat(Files.readString(target.resolve("f.txt"))).isEqualTo("from-a");
        s.rollbackCommitted("a");
        assertThat(target.resolve("f.txt")).doesNotExist();
        assertThat(s.hasCommitted("a")).isFalse();
    }

    @Test
    void committedSnapshotsCanBeReloadedAfterRestart() throws IOException {
        Path target = Files.createDirectories(tmp.resolve("t"));
        SnapshotStore s = new SnapshotStore(target, tmp.resolve("snaps"));
        s.begin("a");
        s.capture("a", "f.txt");
        Files.writeString(target.resolve("f.txt"), "x");
        s.commit("a");

        SnapshotStore reloaded = new SnapshotStore(target, tmp.resolve("snaps"));
        reloaded.loadCommitted(List.of("a", "missing"));
        reloaded.rollbackCommitted("a");

        assertThat(target.resolve("f.txt")).doesNotExist();
    }

    // ---- SafeStop

    @Test
    void safeStopFirstTripWinsAndKillSwitchFileTrips() throws IOException {
        Path kill = tmp.resolve("STOP");
        SafeStop stop = new SafeStop(kill);
        assertThat(stop.stopped()).isFalse();
        assertThat(stop.trip("first")).isTrue();
        assertThat(stop.trip("second")).isFalse();
        assertThat(stop.reason()).isEqualTo("first");

        SafeStop viaFile = new SafeStop(kill);
        Files.writeString(kill, "x");
        assertThat(viaFile.stopped()).isTrue();
        assertThat(viaFile.reason()).contains("kill switch");
    }

    // ---- FaultInjector

    @Test
    void parsesFaultSpecs() {
        ScenarioSpec.FaultSpec f = FaultInjector.parse("impl:3:bad-output:both");
        assertThat(f.node).isEqualTo("impl");
        assertThat(f.times).isEqualTo(3);
        assertThat(f.mode).isEqualTo("bad-output");
        assertThat(f.includeFallback).isTrue();
        ScenarioSpec.FaultSpec d = FaultInjector.parse("impl");
        assertThat(d.times).isEqualTo(1);
        assertThat(d.mode).isEqualTo("exception");
        assertThat(d.includeFallback).isFalse();
    }

    @Test
    void exceptionFaultFiresExactlyTheConfiguredNumberOfTimes() {
        FaultInjector fi = new FaultInjector(List.of(FaultInjector.parse("n:2:exception")));
        assertThatThrownBy(() -> fi.before("n", false)).isInstanceOf(FaultInjector.InjectedFault.class);
        assertThatThrownBy(() -> fi.before("n", false)).isInstanceOf(FaultInjector.InjectedFault.class);
        assertThat(fi.before("n", false)).isNull();
        assertThat(fi.before("other", false)).isNull();
    }

    @Test
    void fallbackIsOnlyAffectedWhenRequested() {
        FaultInjector primaryOnly = new FaultInjector(List.of(FaultInjector.parse("n:5:exception")));
        assertThat(primaryOnly.before("n", true)).isNull();
        FaultInjector both = new FaultInjector(List.of(FaultInjector.parse("n:5:exception:both")));
        assertThatThrownBy(() -> both.before("n", true)).isInstanceOf(FaultInjector.InjectedFault.class);
    }

    @Test
    void outputCorruptionModes() {
        AgentResult clean = AgentResult.of("ok", List.of(), List.of(new FileChange("a/A.java", ChangeKind.CREATE, "package a;\nclass A {}\n")));
        FaultInjector fi = new FaultInjector(List.of(FaultInjector.parse("n:1:bad-output"), FaultInjector.parse("m:1:policy")));

        AgentResult bad = fi.corrupt(fi.before("n", false), clean);
        assertThat(bad.changes().get(0).content()).contains("injected");

        AgentResult policy = fi.corrupt(fi.before("m", false), clean);
        assertThat(policy.changes()).hasSize(2);
        assertThat(policy.changes().get(1).content()).contains("password");

        assertThat(fi.corrupt(null, clean)).isSameAs(clean);
        assertThat(FaultInjector.none().before("x", false)).isNull();
    }
}

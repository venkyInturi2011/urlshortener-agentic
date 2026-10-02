package com.example.orchestrator.graph;

import com.example.orchestrator.model.NodeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.orchestrator.TestKit.node;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowGraphTest {

    private static NodeSpec consuming(NodeSpec n, List<String> consumes, List<String> produces) {
        n.consumes = consumes;
        n.produces = produces;
        return n;
    }

    @Test
    void computesParallelLayersAndJoins() {
        WorkflowGraph g = new WorkflowGraph(List.of(node("a", "x"), node("b", "x", "a"), node("c", "x", "a"), node("d", "x", "b", "c")));

        assertThat(g.layers()).containsExactly(List.of("a"), List.of("b", "c"), List.of("d"));
        assertThat(g.joins()).containsExactly("d");
        assertThat(g.descendants("a")).containsExactlyInAnyOrder("b", "c", "d");
        assertThat(g.descendants("d")).isEmpty();
    }

    @Test
    void rejectsCycles() {
        assertThatThrownBy(() -> new WorkflowGraph(List.of(node("a", "x", "b"), node("b", "x", "a"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cycle");
    }

    @Test
    void rejectsUnknownDependencyAndDuplicates() {
        assertThatThrownBy(() -> new WorkflowGraph(List.of(node("a", "x", "ghost")))).hasMessageContaining("unknown node ghost");
        assertThatThrownBy(() -> new WorkflowGraph(List.of(node("a", "x"), node("a", "x")))).hasMessageContaining("duplicate");
        NodeSpec noId = new NodeSpec();
        assertThatThrownBy(() -> new WorkflowGraph(List.of(noId))).hasMessageContaining("without id");
    }

    @Test
    void unknownNodeLookupFails() {
        WorkflowGraph g = new WorkflowGraph(List.of(node("a", "x")));
        assertThat(g.contains("a")).isTrue();
        assertThat(g.contains("z")).isFalse();
        assertThatThrownBy(() -> g.node("z")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void dirtyClosureFollowsDataFlowNotJustControlFlow() {
        NodeSpec a = consuming(node("a", "x"), List.of("in.1"), List.of("out.a"));
        NodeSpec b = consuming(node("b", "x"), List.of("in.2"), List.of("out.b"));
        NodeSpec c = consuming(node("c", "x", "a", "b"), List.of("out.a", "out.b"), List.of("out.c"));
        NodeSpec d = consuming(node("d", "x", "c"), List.of("out.c"), List.of("out.d"));
        WorkflowGraph g = new WorkflowGraph(List.of(a, b, c, d));

        assertThat(g.dirtyClosure(List.of("in.1"))).containsExactlyInAnyOrder("a", "c", "d");
        assertThat(g.dirtyClosure(List.of("in.2"))).containsExactlyInAnyOrder("b", "c", "d");
        assertThat(g.dirtyClosure(List.of("unrelated"))).isEmpty();
    }

    @Test
    void rendersMermaid() {
        NodeSpec gate = node("g", "x", "a");
        gate.humanGate = true;
        String m = new WorkflowGraph(List.of(node("a", "x"), gate)).toMermaid();
        assertThat(m).contains("flowchart TD").contains("a --> g").contains("HUMAN GATE");
    }
}

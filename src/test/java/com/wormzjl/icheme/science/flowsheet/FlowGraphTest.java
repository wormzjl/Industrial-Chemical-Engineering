package com.wormzjl.icheme.science.flowsheet;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan of a run (batch 2026-11-02-process-simulator, W1). */
class FlowGraphTest {
    private static Calculation unit(String id, String reads, String writes) {
        return Calculation.of(id, wires(reads), wires(writes), (board, report) -> {
        });
    }

    private static List<String> wires(String names) {
        return names.isEmpty() ? List.of() : Arrays.asList(names.split(" "));
    }

    private static List<String> ids(FlowGraph.Group group) {
        return group.order().stream().map(Calculation::id).toList();
    }

    @Test void aLineRunsInOrderWhateverOrderItIsGiven() {
        var plan = FlowGraph.plan(List.of(unit("c", "b2", "c2"), unit("a", "", "a2"), unit("b", "a2", "b2")));
        assertEquals(List.of("a", "b", "c"), plan.stream().map(g -> ids(g).getFirst()).toList());
        assertTrue(plan.stream().allMatch(g -> g.tears().isEmpty()));
    }

    @Test void partsRunBeforeWhatTheyFeed() {
        var plan = FlowGraph.plan(List.of(unit("after", "out", "end"), unit("x", "s2 in", "s1"), unit("y", "s1", "s2 out"), unit("feed", "", "in")));
        assertEquals(3, plan.size());
        assertEquals("feed", ids(plan.get(0)).getFirst());
        assertEquals(2, plan.get(1).order().size());
        assertEquals("after", ids(plan.get(2)).getFirst());
    }

    /** Two loops that share a stream take one tear: the shared one. */
    @Test void twoLoopsThatShareAStreamTakeOneTear() {
        var plan = FlowGraph.plan(List.of(unit("a", "back1 back2", "shared"), unit("b", "shared", "back1 on"), unit("c", "on", "back2")));
        assertEquals(1, plan.size());
        assertEquals(List.of("shared"), plan.getFirst().tears());
        assertEquals(List.of("b", "c", "a"), ids(plan.getFirst()), "the tear's reader starts the pass");
    }

    /** Two loops with nothing shared but a unit need two tears. */
    @Test void loopsThroughOneUnitTakeATearEach() {
        var plan = FlowGraph.plan(List.of(unit("a", "b1 c1", "a1 a2"), unit("b", "a1", "b1"), unit("c", "a2", "c1")));
        assertEquals(1, plan.size());
        assertEquals(2, plan.getFirst().tears().size());
    }

    /** Among equal tears the loop cuts at a block's outlet, so every pass feeds the block a stream it has worked out. */
    @Test void aLoopTearsAtABlocksOutlet() {
        var loop = new ArrayList<>(List.of(unit("a", "s3", "s1"), unit("b", "s1", "s2"), unit("column", "s2", "s3").asHeavy()));
        assertEquals(List.of("s3"), FlowGraph.plan(loop).getFirst().tears());
        loop.set(2, unit("column", "s2", "s3"));
        loop.set(1, unit("b", "s1", "s2").asHeavy());
        assertEquals(List.of("s2"), FlowGraph.plan(loop).getFirst().tears());
    }

    /** A tear comes from the streams; a link between a unit's sides stays whole. */
    @Test void aLinkIsNeverTorn() {
        var hot = new Calculation("hot", "E", List.of("h"), List.of("h2"), List.of(), List.of("duty"), false, (board, report) -> {
        });
        var cold = new Calculation("cold", "E", List.of("c"), List.of("h"), List.of("duty"), List.of(), false, (board, report) -> {
        });
        var plan = FlowGraph.plan(List.of(hot, cold));
        assertEquals(List.of("h"), plan.getFirst().tears());
        assertEquals(List.of("hot", "cold"), ids(plan.getFirst()));
    }

    @Test void aUnitThatFeedsItselfIsALoop() {
        var plan = FlowGraph.plan(List.of(unit("a", "x", "x")));
        assertEquals(List.of("x"), plan.getFirst().tears());
    }

    @Test void twoWritersOfOneWireAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> FlowGraph.plan(List.of(unit("a", "", "x"), unit("b", "", "x"))));
    }

    /** The same sheet gives the same plan: a result can be reproduced. */
    @Test void thePlanIsDeterministic() {
        var sheet = List.of(unit("a", "s6 in", "s1"), unit("b", "s1 s5", "s2"), unit("c", "s2", "s3 s4"), unit("d", "s3", "s5"), unit("e", "s4", "s6 out"), unit("in", "", "in"));
        var first = FlowGraph.plan(sheet);
        for (int i = 0; i < 20; i++) {
            var again = FlowGraph.plan(sheet);
            assertEquals(first.size(), again.size());
            for (int k = 0; k < first.size(); k++) {
                assertEquals(first.get(k).tears(), again.get(k).tears());
                assertEquals(ids(first.get(k)), ids(again.get(k)));
            }
        }
    }

    /** A ring of rings too wide to search still breaks: the greedy tear leaves no cycle and gives back what it did not need. */
    @Test void aLoopTooLargeToSearchStillBreaks() {
        var sheet = new ArrayList<Calculation>();
        int rings = 40;
        for (int i = 0; i < rings; i++) {
            sheet.add(unit("a" + i, "z" + ((i + rings - 1) % rings) + " back" + i, "a" + i + "b" + i));
            sheet.add(unit("b" + i, "a" + i + "b" + i, "b" + i + "c" + i + " back" + i));
            sheet.add(unit("c" + i, "b" + i + "c" + i, "z" + i));
        }
        var plan = FlowGraph.plan(sheet);
        assertEquals(1, plan.size());
        assertEquals(sheet.size(), plan.getFirst().order().size());
        assertTrue(plan.getFirst().tears().size() >= rings / 10, "the rings are many, each needs a cut");
    }
}

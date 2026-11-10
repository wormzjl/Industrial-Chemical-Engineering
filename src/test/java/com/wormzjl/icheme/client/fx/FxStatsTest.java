package com.wormzjl.icheme.client.fx;

import com.wormzjl.icheme.client.fx.FxStats.Counter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The effects' counters and their F3 line (batch 2026-11-10-custom-shader-system, FX_BENCH_PLAN.md). */
class FxStatsTest {
    @BeforeEach
    @AfterEach
    void reset() {
        FxStats.reset();
    }

    @Test
    void frameCountersRollAtTheFrameStartAndKeepTheirTotals() {
        FxStats.frame(FxPath.SHADER);
        FxStats.add(Counter.QUADS, 300);
        FxStats.add(Counter.QUADS, 20);
        FxStats.add(Counter.REBUILDS, 2);
        assertEquals(320, FxStats.now(Counter.QUADS));
        assertEquals(0, FxStats.last(Counter.QUADS));
        FxStats.frame(FxPath.SHADER);
        assertEquals(0, FxStats.now(Counter.QUADS));
        assertEquals(320, FxStats.last(Counter.QUADS));
        assertEquals(2, FxStats.last(Counter.REBUILDS));
        FxStats.add(Counter.QUADS, 100);
        FxStats.frame(FxPath.SHADER);
        assertEquals(100, FxStats.last(Counter.QUADS));
        assertEquals(0, FxStats.last(Counter.REBUILDS), "a frame with no rebuild shows none");
        assertEquals(420, FxStats.total(Counter.QUADS));
        assertEquals(2, FxStats.total(Counter.REBUILDS));
        assertEquals(3, FxStats.frames());
    }

    @Test
    void gaugesHoldTheirValueAcrossFrames() {
        FxStats.set(Counter.LOOPS, 12);
        FxStats.add(Counter.VERTEX_BYTES, 4096);
        FxStats.add(Counter.VERTEX_BYTES, -1024);
        FxStats.frame(FxPath.COMPATIBLE);
        FxStats.frame(FxPath.COMPATIBLE);
        assertEquals(12, FxStats.now(Counter.LOOPS));
        assertEquals(12, FxStats.last(Counter.LOOPS));
        assertEquals(3072, FxStats.total(Counter.VERTEX_BYTES));
        FxStats.set(Counter.LOOPS, 3);
        assertEquals(3, FxStats.last(Counter.LOOPS));
    }

    @Test
    void aFrameCounterCannotBeStored() {
        assertThrows(IllegalArgumentException.class, () -> FxStats.set(Counter.QUADS, 1));
    }

    @Test
    void everyCounterHasAScope() {
        for (var c : Counter.values()) assertEquals(c.name().endsWith("_NS") && c != Counter.SOUND_NS || c == Counter.QUADS || c == Counter.EMITTERS
                || c == Counter.REBUILDS || c == Counter.WIRING_BUILDS ? FxStats.Scope.FRAME : FxStats.Scope.GAUGE, c.scope, c.name());
    }

    @Test
    void theDebugLineShowsPathQuadsEmittersAndLoops() {
        assertEquals("IChemE fx: COMPATIBLE, 0 quads, 0 emitters, 0 loops", FxStats.debugLine());
        FxStats.frame(FxPath.SHADER);
        FxStats.add(Counter.QUADS, 1500);
        FxStats.add(Counter.EMITTERS, 42);
        FxStats.set(Counter.LOOPS, 12);
        // This frame is not whole yet: the line shows the last frame's.
        assertEquals("IChemE fx: SHADER, 0 quads, 0 emitters, 12 loops", FxStats.debugLine());
        FxStats.frame(FxPath.OFF);
        assertEquals("IChemE fx: OFF, 1500 quads, 42 emitters, 12 loops", FxStats.debugLine());
        assertEquals(FxPath.OFF, FxStats.path());
    }
}

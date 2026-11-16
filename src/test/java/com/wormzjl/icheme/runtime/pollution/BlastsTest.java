package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.PollutionField;
import com.wormzjl.icheme.science.pollution.PollutionSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure parts of ignition and blasts (batch 2026-11-15-pollution-system, WP6a; plan section 7): the LFL threshold, the
 * chain into flammable face neighbours only, the blast queue's per-tick cap, due ticks and one entry per cube, the power
 * from the index and the air share, the lit rule of a source block, and the burst sound's phase and volume.
 */
class BlastsTest {
    private static final long A = CubeKey.of(0, 4, 0);

    @Test
    void ignitesFromTheLowerFlammabilityLimitUp() {
        assertFalse(Ignition.ignites(0.999));
        assertTrue(Ignition.ignites(1.0));
        assertTrue(Ignition.ignites(7.5));
        assertFalse(Ignition.ignites(0.9));
        assertFalse(Ignition.ignites(Double.NaN));
    }

    @Test
    void theChainPicksOnlyNeighboursAtOrAboveOne() {
        var index = Map.of(
                CubeKey.offset(A, -1, 0, 0), 1.0,
                CubeKey.offset(A, 1, 0, 0), 0.99,
                CubeKey.offset(A, 0, -1, 0), 2.0,
                CubeKey.offset(A, 0, 1, 0), 0.0,
                CubeKey.offset(A, 0, 0, -1), 1.5,
                CubeKey.offset(A, 0, 0, 1), Double.NaN);
        var chain = Blasts.chain(A, k -> index.getOrDefault(k, 0.0));
        assertEquals(List.of(CubeKey.offset(A, -1, 0, 0), CubeKey.offset(A, 0, -1, 0), CubeKey.offset(A, 0, 0, -1)), chain);
        assertTrue(Blasts.chain(A, k -> 0.5).isEmpty(), "no flammable neighbour, no chain");
    }

    @Test
    void theQueueServesFourPerTickByDueTick() {
        var queue = new Blasts.Queue();
        for (int i = 0; i < 6; i++) {
            assertTrue(queue.offer(CubeKey.of(i, 0, 0), i, 0, 0, 10));
        }
        assertTrue(queue.offer(CubeKey.of(0, 1, 0), 0, 16, 0, 13));
        assertTrue(queue.offer(CubeKey.of(1, 1, 0), 16, 16, 0, 13));
        assertTrue(queue.take(9, Blasts.BLASTS_PER_TICK).isEmpty(), "nothing is due before tick 10");
        var first = queue.take(10, Blasts.BLASTS_PER_TICK);
        assertEquals(4, first.size(), "at most four per tick");
        for (int i = 0; i < 4; i++) {
            assertEquals(CubeKey.of(i, 0, 0), first.get(i).cube(), "in the order queued");
        }
        var second = queue.take(11, Blasts.BLASTS_PER_TICK);
        assertEquals(2, second.size(), "the rest of tick 10 waits a tick");
        assertTrue(queue.take(12, Blasts.BLASTS_PER_TICK).isEmpty(), "the chain is due 3 ticks after");
        var chained = queue.take(13, Blasts.BLASTS_PER_TICK);
        assertEquals(2, chained.size());
        assertEquals(13, chained.get(0).due());
        assertEquals(0, queue.size());
    }

    @Test
    void aLaterDueQueuedFirstWaitsForAnEarlierOne() {
        var queue = new Blasts.Queue();
        assertTrue(queue.offer(CubeKey.of(5, 0, 0), 0, 0, 0, 13));
        assertTrue(queue.offer(CubeKey.of(6, 0, 0), 0, 0, 0, 11));
        var due = queue.take(13, Blasts.BLASTS_PER_TICK);
        assertEquals(List.of(CubeKey.of(6, 0, 0), CubeKey.of(5, 0, 0)), due.stream().map(Blasts.Blast::cube).toList());
    }

    @Test
    void noCubeIsQueuedTwice() {
        var queue = new Blasts.Queue();
        assertTrue(queue.offer(A, 0, 64, 0, 5));
        assertFalse(queue.offer(A, 3, 70, 3, 8), "a waiting cube is not queued again");
        assertTrue(queue.waits(A));
        assertEquals(1, queue.take(8, Blasts.BLASTS_PER_TICK).size());
        assertFalse(queue.waits(A));
        assertTrue(queue.offer(A, 0, 64, 0, 9), "served, it may be queued again");
    }

    @Test
    void theQueueDropsTheNewestBeyondItsCap() {
        var queue = new Blasts.Queue();
        for (int i = 0; i < Blasts.QUEUE_CAP; i++) {
            assertTrue(queue.offer(CubeKey.of(i, 0, 0), 0, 0, 0, 1));
        }
        assertFalse(queue.offer(CubeKey.of(-1, 0, 0), 0, 0, 0, 1));
        assertEquals(1, queue.dropped());
        assertEquals(Blasts.QUEUE_CAP, queue.size());
    }

    @Test
    void powerFromTheIndexAndTheAirShare() {
        // Plan section 7: an open cube at the LFL is about one TNT block (4); the index burns at most 2 (about 5);
        // a 200 m³ room at the LFL about 1.4; a pocket of a few m³ gives no blast.
        assertEquals(4.0, Blasts.power(1.0, 1.0, 8), 0.1);
        assertEquals(Blasts.power(2.0, 1.0, 8), Blasts.power(5.0, 1.0, 8), 0.0, "index 5 burns as 2");
        assertEquals(4.97, Blasts.power(5.0, 1.0, 8), 0.05);
        assertEquals(1.44, Blasts.power(1.0, 200.0 / PollutionField.CUBE_VOLUME, 8), 0.05);
        assertTrue(Blasts.power(1.0, 0.001, 8) < Blasts.MINIMUM_POWER, "a 4 m³ pocket burns without a blast");
        assertEquals(3.0, Blasts.power(2.0, 1.0, 3), 0.0, "capped");
        assertEquals(0.0, Blasts.power(0.0, 1.0, 8), 0.0);
    }

    @Test
    void theBurnTakesTheBurntIndexOfTheAir() {
        double closed = PollutionSettings.DEFAULT.closedAirShare();
        assertEquals(2.0 * PollutionField.CUBE_AIR_MOLES, Blasts.burntAmount(5.0, 1.0, closed), 1e-6);
        assertEquals(1.2 * 0.5 * PollutionField.CUBE_AIR_MOLES, Blasts.burntAmount(1.2, 0.5, closed), 1e-6);
        assertEquals(1.0 * closed * PollutionField.CUBE_AIR_MOLES, Blasts.burntAmount(1.0, 0.0, closed), 1e-9, "floored at the closed share, as the index is");
    }

    @Test
    void aSourceBlockCountsOnlyWhileLit() {
        assertTrue(Ignition.lit(false, false), "a torch has no lit property: always a flame");
        assertTrue(Ignition.lit(true, true), "a lit furnace");
        assertFalse(Ignition.lit(true, false), "an unlit campfire");
        for (String heat : List.of("fading", "kindled", "seething")) {
            assertTrue(Ignition.blazeLit(heat), heat);
        }
        for (String heat : List.of("none", "smouldering", "")) {
            assertFalse(Ignition.blazeLit(heat), heat);
        }
        assertEquals(3, Ignition.rotate(3, 10));
        assertEquals(0, Ignition.rotate(12, 0));
        assertEquals(2, Ignition.rotate(12, 10));
    }

    @Test
    void theBurstSoundByPhaseAndMass() {
        assertEquals(BurstPipes.Phase.EMPTY, BurstPipes.phase(0, 0));
        assertEquals(BurstPipes.Phase.EMPTY, BurstPipes.phase(1e-4, 1e-4));
        assertEquals(BurstPipes.Phase.GAS, BurstPipes.phase(0.05, 0.01));
        assertEquals(BurstPipes.Phase.LIQUID, BurstPipes.phase(0.05, 4.0));
        assertEquals(BurstPipes.Phase.GAS, BurstPipes.phase(1.0, 1.0), "gas on a tie");
        assertEquals(1f, BurstPipes.volume(0.0));
        assertEquals(1f, BurstPipes.volume(BurstPipes.QUIET_KILOGRAMS));
        assertEquals(2f, BurstPipes.volume(1.0), 1e-6f);
        assertEquals(3f, BurstPipes.volume(BurstPipes.LOUD_KILOGRAMS), 1e-6f);
        assertEquals(3f, BurstPipes.volume(1e5));
        assertEquals(1f, BurstPipes.volume(Double.NaN));
    }
}

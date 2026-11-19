package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The water body rule of a water intake (batch 2026-11-18-water-intake-outfall, {@link WaterBody}): one of its faces touches
 * water that belongs to at least 100 face-connected water blocks; the fill is bounded. The probe stands for the world: in it a
 * waterlogged block and a flowing block are water as a source is (their fluid is in the {@code minecraft:water} tag), and a
 * block in a chunk that is not loaded is not.
 */
class WaterBodyTest {
    /** A pool of {@code count} water blocks in a row along +x from (1, 0, 0), beside an intake at the origin. */
    private static Set<Long> row(int count) {
        var water = new HashSet<Long>();
        for (int i = 1; i <= count; i++) water.add(WaterBody.key(i, 0, 0));
        return water;
    }

    private static WaterBody.Count count(Set<Long> water) {
        return WaterBody.count(0, 0, 0, (x, y, z) -> water.contains(WaterBody.key(x, y, z)));
    }

    @Test void ninetyNineBlocksAreNoBodyAndAHundredAre() {
        var short_ = count(row(99));
        assertFalse(short_.body(), "99 blocks: closed");
        assertEquals(99, short_.blocks());
        var enough = count(row(100));
        assertTrue(enough.body(), "100 blocks: open");
        assertEquals(WaterBody.WATER_BODY, enough.blocks());
    }

    @Test void aWaterloggedOrFlowingBlockIsWaterAsASourceIs() {
        // The probe answers by the block's fluid: a waterlogged block (a slab, a fence) and a flowing block both hold water.
        var waterlogged = row(99);
        waterlogged.add(WaterBody.key(100, 0, 0));
        assertTrue(count(waterlogged).body(), "a waterlogged block closes the gap to 100");
        // An intake beside one waterlogged block that joins a large pool through it.
        var pool = new HashSet<Long>();
        pool.add(WaterBody.key(0, 0, 1));
        for (int x = -5; x <= 5; x++) for (int z = 2; z <= 12; z++) pool.add(WaterBody.key(x, 0, z));
        assertTrue(count(pool).body(), "the flowing or waterlogged block at a face joins the body behind it");
    }

    @Test void twoPoolsMeetingOnlyAtTheIntakeAreTwoBodies() {
        var water = new HashSet<Long>();
        for (int i = 1; i <= 60; i++) water.add(WaterBody.key(i, 0, 0));
        for (int i = 1; i <= 60; i++) water.add(WaterBody.key(-i, 0, 0));
        var c = count(water);
        assertFalse(c.body(), "60 and 60 blocks on two faces are no body of 100: " + c);
        assertEquals(60, c.blocks());
    }

    @Test void theFillIsBoundedWhateverTheWorldHolds() {
        // An ocean: every block but the intake is water. The fill stops at 100 blocks.
        var ocean = WaterBody.count(0, 0, 0, (x, y, z) -> true);
        assertTrue(ocean.body());
        assertTrue(ocean.reads() <= 6 + 6 * WaterBody.WATER_BODY, "one body: at most 606 reads: " + ocean.reads());
        // Six separate pools of 99 on the six faces, each a closed 1-wide line out of a face: the worst case.
        var six = new HashSet<Long>();
        int[][] faces = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (var f : faces) for (int i = 1; i <= 99; i++) six.add(WaterBody.key(f[0] * i, f[1] * i, f[2] * i));
        var worst = count(six);
        assertFalse(worst.body());
        assertTrue(worst.reads() <= WaterBody.MAX_READS, "at most " + WaterBody.MAX_READS + " reads: " + worst.reads());
        // No water at all: the six faces only.
        assertEquals(6, count(Set.of()).reads());
    }
}

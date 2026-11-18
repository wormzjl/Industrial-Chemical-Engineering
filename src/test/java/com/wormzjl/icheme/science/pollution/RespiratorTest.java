package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.Exposure.Level;
import org.junit.jupiter.api.Test;

/** The respirator's law (batch 2026-11-18-respirator): protection factor 50, filter used by the load it takes out. */
class RespiratorTest {
    private static final double FULL = Respirator.FILTER_CAPACITY;
    /** One effects pass: 20 ticks, one play second. */
    private static final double PASS = 1.0;

    @Test
    void constants() {
        assertEquals(50, Respirator.PROTECTION_FACTOR);
        assertEquals(600, Respirator.FILTER_CAPACITY);
    }

    @Test
    void thresholdsMoveByTheProtectionFactor() {
        assertEquals(Level.NONE, Exposure.level(Respirator.breathed(4.999, FULL)));
        assertEquals(Level.LOW, Exposure.level(Respirator.breathed(5, FULL)), "effects start at 5");
        assertEquals(Level.LOW, Exposure.level(Respirator.breathed(14.999, FULL)));
        assertEquals(Level.MEDIUM, Exposure.level(Respirator.breathed(15, FULL)), "MEDIUM at 15");
        assertEquals(Level.MEDIUM, Exposure.level(Respirator.breathed(49.999, FULL)));
        assertEquals(Level.HIGH, Exposure.level(Respirator.breathed(50, FULL)), "damage at 50");
        assertEquals(Level.HIGH, Exposure.level(Respirator.breathed(149.999, FULL)));
        assertEquals(Level.EXTREME, Exposure.level(Respirator.breathed(150, FULL)), "EXTREME at 150");
    }

    @Test
    void noFilterOrSpentFilterPassesEverything() {
        for (double index : new double[] {0, 0.1, 1, 5, 50, 150}) {
            assertEquals(index, Respirator.breathed(index, 0), "spent at " + index);
            assertEquals(index, Respirator.breathed(index, Double.NaN), "unknown at " + index);
        }
        assertFalse(Respirator.protects(0));
        assertFalse(Respirator.protects(-1));
        assertTrue(Respirator.protects(Double.MIN_VALUE));
        double[] cube = {2, 0.5, 0.05, 4};
        Level[] levels = Respirator.breathedLevels(cube, 0);
        for (int i = 0; i < cube.length; i++) {
            assertEquals(Exposure.level(cube[i]), levels[i], "channel " + i);
        }
    }

    /** Protected passes of a full filter in a cube that holds {@code index} on one channel. */
    private static int life(double index) {
        double remaining = FULL;
        double[] cube = {index, 0, 0, 0};
        int protectedPasses = 0;
        for (int pass = 0; pass < 1_000_000; pass++) {
            if (!Respirator.protects(remaining)) {
                return protectedPasses;
            }
            protectedPasses++;
            remaining = Respirator.after(remaining, Respirator.load(cube, PASS));
        }
        return -1;
    }

    @Test
    void expectedLives() {
        assertEquals(600, life(1), "10 min at index 1");
        assertEquals(6000, life(0.1), "100 min at 0.1");
        assertEquals(120, life(5), "2 min at 5");
        assertEquals(12, life(50), "12 s at 50");
    }

    @Test
    void nothingIsUsedBelowLowOnEveryChannel() {
        double below = Math.nextDown(Exposure.LOW);
        assertEquals(0, Respirator.load(new double[] {below, below, below, below}, PASS));
        assertEquals(FULL, Respirator.after(FULL, Respirator.load(new double[] {below, 0, Double.NaN, -1}, PASS)));
        assertEquals(0.5, Respirator.load(new double[] {0.1, below, 0.4, 0}, PASS), 1e-15, "only channels at LOW count");
        assertEquals(2, Respirator.load(new double[] {1, 0, 0, 0}, 2), "load scales with the pass seconds");
    }

    @Test
    void thePassThatEmptiesTheFilterIsStillProtected() {
        double[] cube = {50, 0, 0, 0};
        double remaining = 30;
        // This pass: protected by the 30 left before its load of 50.
        assertTrue(Respirator.protects(remaining));
        assertEquals(Level.EXTREME, Exposure.level(cube[0]), "the cube's own level");
        assertEquals(Level.HIGH, Respirator.breathedLevels(cube, remaining)[0]);
        remaining = Respirator.after(remaining, Respirator.load(cube, PASS));
        assertEquals(0, remaining);
        // The next pass: spent, the cube's own level.
        assertFalse(Respirator.protects(remaining));
        assertEquals(Level.EXTREME, Respirator.breathedLevels(cube, remaining)[0]);
        assertEquals(0, Respirator.after(remaining, 50), "a spent filter stays spent");
    }

    @Test
    void protectedLevelsAtTheThresholds() {
        Level[] levels = Respirator.breathedLevels(new double[] {50, 15, 5, 150}, FULL);
        assertArrayEquals(new Level[] {Level.HIGH, Level.MEDIUM, Level.LOW, Level.EXTREME}, levels);
    }

    /** No respirator worn, or one with no working filter: the pass uses the cube's own levels array, untouched. */
    @Test
    void withoutWorkingGearTheCubesLevelsAreUsedAsTheyAre() {
        double[] cube = {2, 0.5, 0.35, 4};
        Level[] levels = new Level[cube.length];
        for (int i = 0; i < cube.length; i++) {
            levels[i] = Exposure.level(cube[i]);
        }
        Level[] before = levels.clone();
        assertSame(levels, Respirator.protect(false, FULL, cube, levels), "no gear");
        assertSame(levels, Respirator.protect(true, 0, cube, levels), "spent filter");
        assertSame(levels, Respirator.protect(true, Double.NaN, cube, levels), "no filter");
        assertArrayEquals(before, levels, "nothing written");
        assertArrayEquals(new Level[] {Level.NONE, Level.NONE, Level.NONE, Level.NONE}, Respirator.protect(true, FULL, cube, levels), "worn: all below 5");
        assertArrayEquals(before, levels, "the cube's levels stay for the armour");
    }

    @Test
    void share() {
        assertEquals(1, Respirator.share(FULL));
        assertEquals(0.5, Respirator.share(300));
        assertEquals(0, Respirator.share(0));
        assertEquals(0, Respirator.share(Double.NaN));
        assertEquals(1, Respirator.share(2 * FULL));
    }
}

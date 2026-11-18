package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The ground's world rules (batch 2026-11-18-ground-pollution, G4) on a column's one byte. */
class GroundEffectsTest {
    private static int value(double index) {
        return MapColumns.quantise(index);
    }

    @Test void growthStopsAtAnIndexOfZeroPointThree() {
        assertFalse(GroundEffects.blocksGrowth(0), "clean ground grows");
        assertFalse(GroundEffects.blocksGrowth(value(0.28)));
        assertTrue(GroundEffects.blocksGrowth(value(0.30)));
        assertTrue(GroundEffects.blocksGrowth(value(1.2)));
    }

    @Test void waterActsOnTheToxicTableAtTheGroundIndex() {
        assertNull(GroundEffects.water(0));
        assertNull(GroundEffects.water(value(0.08)), "below LOW nothing acts");
        var levels = GroundEffects.water(value(0.46));
        assertEquals(Exposure.Level.MEDIUM, levels[Channel.TOXIC.ordinal()]);
        assertEquals(Exposure.Level.NONE, levels[Channel.CORROSIVE.ordinal()], "one damage source, the toxic one");
        assertEquals(Exposure.Level.NONE, levels[Channel.PARTICULATE.ordinal()]);
        assertEquals(Exposure.Level.NONE, levels[Channel.COMBUSTIBLE.ordinal()]);
        assertEquals(Exposure.Level.HIGH, GroundEffects.water(value(1.2))[Channel.TOXIC.ordinal()]);
    }
}

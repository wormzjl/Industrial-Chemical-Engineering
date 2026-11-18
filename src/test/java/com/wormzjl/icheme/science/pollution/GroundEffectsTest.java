package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The ground's world rules (batch 2026-11-18-ground-pollution, G4) on a column's four bytes. */
class GroundEffectsTest {
    private static int packed(Channel channel, double index) {
        return MapColumns.quantise(index) << 8 * channel.ordinal();
    }

    @Test void growthStopsAtAWorstIndexOfZeroPointThree() {
        assertFalse(GroundEffects.blocksGrowth(0), "clean ground grows");
        assertFalse(GroundEffects.blocksGrowth(packed(Channel.TOXIC, 0.28)));
        assertTrue(GroundEffects.blocksGrowth(packed(Channel.TOXIC, 0.30)));
        assertTrue(GroundEffects.blocksGrowth(packed(Channel.PARTICULATE, 1.2)), "any channel counts");
        assertTrue(GroundEffects.blocksGrowth(packed(Channel.TOXIC, 0.1) | packed(Channel.CORROSIVE, 0.4)), "the worst channel decides");
    }

    @Test void waterActsWithItsToxicAndCorrosiveIndicesOnly() {
        assertNull(GroundEffects.water(0));
        assertNull(GroundEffects.water(packed(Channel.PARTICULATE, 3) | packed(Channel.COMBUSTIBLE, 3)), "combustible and particulate do not act on a swimmer");
        var levels = GroundEffects.water(packed(Channel.TOXIC, 0.46) | packed(Channel.CORROSIVE, 1.2) | packed(Channel.PARTICULATE, 3));
        assertEquals(Exposure.Level.MEDIUM, levels[Channel.TOXIC.ordinal()]);
        assertEquals(Exposure.Level.HIGH, levels[Channel.CORROSIVE.ordinal()]);
        assertEquals(Exposure.Level.NONE, levels[Channel.PARTICULATE.ordinal()]);
        assertEquals(Exposure.Level.NONE, levels[Channel.COMBUSTIBLE.ordinal()]);
        assertNull(GroundEffects.water(packed(Channel.TOXIC, 0.08)), "below LOW nothing acts");
    }
}

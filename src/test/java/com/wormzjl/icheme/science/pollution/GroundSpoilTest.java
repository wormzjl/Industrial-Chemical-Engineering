package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The spoiling law of polluted ground (batch 2026-11-18-ground-pollution, G4b): limits, rate, half-times. */
class GroundSpoilTest {
    private static int packed(Channel channel, double index) {
        return MapColumns.quantise(index) << 8 * channel.ordinal();
    }

    @Test void halfOfAColumnSpoilsInTenMinutesAtIndexOne() {
        assertEquals(0.2952, GroundSpoil.ATTEMPTS_PER_INDEX, 1e-4);
        assertEquals(0.5, GroundSpoil.spoiledShare(1.0, 600), 1e-12);
        assertEquals(600, GroundSpoil.halfSeconds(1.0), 1e-9);
        assertEquals(2000, GroundSpoil.halfSeconds(0.3), 1e-9, "index 0.3: 33 min");
        assertEquals(200, GroundSpoil.halfSeconds(3.0), 1e-9, "index 3: 3.3 min");
        assertEquals(Double.POSITIVE_INFINITY, GroundSpoil.halfSeconds(0.29));
        assertEquals(GroundSpoil.MAXIMUM_ATTEMPTS, GroundSpoil.expectedAttempts(100), 0.0, "at most 8 picks a pass");
    }

    @Test void theRandomRoundingKeepsTheMeanExact() {
        double expected = GroundSpoil.expectedAttempts(1.0);
        int sum = 0, n = 10_000;
        for (int i = 0; i < n; i++) sum += GroundSpoil.attempts(1.0, (i + 0.5) / n);
        assertEquals(expected, sum / (double) n, 1e-4);
        assertEquals(0, GroundSpoil.attempts(0.2, 0.0), "below the limit: no pick");
        assertEquals(8, GroundSpoil.attempts(50, 0.99));
    }

    @Test void limitsReadTheWorstChannel() {
        assertTrue(GroundSpoil.recovers(0), "a column with no entry is clean");
        assertTrue(GroundSpoil.recovers(packed(Channel.TOXIC, 0.08)));
        assertFalse(GroundSpoil.recovers(packed(Channel.PARTICULATE, 0.1)));
        assertEquals(0.3, GroundSpoil.worst(packed(Channel.TOXIC, 0.1) | packed(Channel.CORROSIVE, 0.3)), 1e-12);
        assertEquals(GroundEffects.GROWTH_LIMIT, GroundSpoil.SPOIL_LIMIT, 0.0, "spoiling starts where growth stops");
    }
}

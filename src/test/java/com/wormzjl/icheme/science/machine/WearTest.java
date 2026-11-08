package com.wormzjl.icheme.science.machine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** W10b (build plan revision 2, section 1): a pump's wear from cavitation and from running dry. */
class WearTest {
    private static double hours(double sigma) {
        return Wear.life(sigma, false, Wear.Settings.DEFAULT) / 3600;
    }

    @Test
    void cavitationLivesMatchThePlan() {
        assertEquals(1, Wear.intensity(Wear.PEAK), 1e-12, "the erosion peak is one");
        assertEquals(2.0, hours(2.06), .05, "2.0 h at the peak");
        assertEquals(5.1, hours(1), .05, "5.1 h at NPSH3");
        assertEquals(18.9, hours(3.5), .05, "18.9 h at sigma 3.5");
        // The peak is the shortest life.
        for (double s = .1; s < 4; s += .01) assertTrue(hours(s) >= 2.0 - 1e-9, "sigma " + s);
    }

    @Test
    void nothingErodesFromInceptionUpOrWithoutARead() {
        for (double s : new double[]{4, 4.5, 10, Double.POSITIVE_INFINITY, Double.NaN, 0, -1}) {
            assertEquals(0, Wear.intensity(s), "sigma " + s);
            assertEquals(0, Wear.rate(s, false), "sigma " + s);
        }
        assertTrue(Wear.intensity(3.99) > 0);
    }

    @Test
    void runningDryLastsTenMinutesAndTheSwitchStopsAllWear() {
        assertEquals(600, Wear.life(Double.NaN, true, Wear.Settings.DEFAULT), 1e-9);
        var off = new Wear.Settings(false, 2, 10);
        assertEquals(0, Wear.rate(2.06, true, off));
        // The config scales each life.
        var fast = new Wear.Settings(true, 1, 5);
        assertEquals(3600, Wear.life(2.06, false, fast), 3600 * .01);
        assertEquals(300, Wear.life(Double.NaN, true, fast), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new Wear.Settings(true, 0, 10));
    }
}

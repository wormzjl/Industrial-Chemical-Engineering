package com.wormzjl.icheme.science.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Plan section 6.1: the digest of a state on a hand-made compound list. */
class CorrosionStandardTest {
    // H2S, CO2, two crude cuts (CHx, sulfur from the sub-record), water last.
    private static final double[] MOLAR = {34.08e-3, 44.01e-3, 200e-3, 400e-3, 18.015e-3};
    private static final double[][] FORMULAS = {{0, 2, 0, 0, 1}, {1, 0, 0, 2, 0}, {14, 30, 0, 0, 0}, {28, 56, 0, 0, 0}, {0, 2, 0, 1, 0}};
    private static final double[] SULFUR = {0, 0, .5, 3}, ACID = {0, 0, 0, 2}, FOULING = {0, 0, .0005, .0018};

    private static CorrosionStandard standard() {
        return new CorrosionStandard(MOLAR, FORMULAS, append(SULFUR), append(ACID), append(FOULING));
    }

    private static double[] append(double[] cuts) {
        var out = new double[5];
        System.arraycopy(cuts, 0, out, 0, 4);
        return out;
    }

    @Test
    void theDigestOfAHandMadeList() {
        var s = standard();
        assertEquals(5, s.size());
        assertEquals(4, s.water());
        assertEquals(100 * 32.06 / 34.08, s.sulfur(0), 1e-9, "H2S by its formula");
        assertEquals(3, s.sulfur(3), "a cut by its sub-record");
        double[] moles = {1, 2, 3, 1, 3}, oil = {0, 0, 3, 1, 0}, aqueous = {0, 0, 0, 0, 3};
        var d = s.digest(moles, 600, 20e5, oil, aqueous);
        assertEquals(600, d.temperature());
        assertTrue(d.wet());
        assertTrue(d.oil());
        assertEquals(1 / 10. * 20, d.h2s(), 1e-12, "y P in bar");
        assertEquals(2 / 10. * 20, d.co2(), 1e-12);
        assertEquals(0, d.so2());
        assertEquals(0, d.nh3());
        assertEquals(0, d.o2());
        assertEquals(0, d.h2(), "no hydrogen on the list");
        double m2 = 3 * .2, m3 = .4;
        assertEquals((m2 * .5 + m3 * 3) / (m2 + m3), d.sulfur(), 1e-12, "mass-weighted over the oil");
        assertEquals(m3 * 2 / (m2 + m3), d.acid(), 1e-12);
        assertEquals((m2 * .0005 + m3 * .0018) / (m2 + m3), d.fouling(), 1e-15);
        // A trace of a liquid is none.
        var dry = s.digest(moles, 600, 20e5, new double[]{0, 0, 1e-10, 0, 0}, new double[5]);
        assertFalse(dry.oil());
        assertFalse(dry.wet());
        assertEquals(0, dry.sulfur());
        assertFalse(s.digest(moles, 600, 20e5, null, null).oil(), "no phase split: no liquid");
    }

    @Test
    void aListWithoutCrudeCutsGivesNoSulfur() {
        double[] molar = {16.04e-3, 44.01e-3, 31.998e-3, 2.016e-3, 17.031e-3, 64.066e-3, 18.015e-3};
        double[][] formulas = {{1, 4, 0, 0, 0}, {1, 0, 0, 2, 0}, {0, 0, 0, 2, 0}, {0, 2, 0, 0, 0}, {0, 3, 1, 0, 0}, {0, 0, 0, 2, 1}, {0, 2, 0, 1, 0}};
        var zero = new double[7];
        var s = new CorrosionStandard(molar, formulas, zero, zero, zero);
        double[] moles = {1, 1, 1, 1, 1, 1, 4};
        var d = s.digest(moles, 300, 10e5, new double[]{1, 0, 0, 0, 0, 0, 0}, new double[]{0, 0, 0, 0, 0, 0, 4});
        assertEquals(0, d.sulfur());
        assertEquals(0, d.acid());
        assertEquals(1, d.co2(), 1e-12);
        assertEquals(1, d.o2(), 1e-12);
        assertEquals(1, d.h2(), 1e-12);
        assertEquals(1, d.nh3(), 1e-12);
        assertEquals(1, d.so2(), 1e-12);
        assertEquals(0, d.h2s());
        assertEquals(6, s.water());
    }

    @Test
    void anEmptyOrDamagedStateGivesAFiniteDigest() {
        var s = standard();
        assertSame(CorrosionDigest.EMPTY, s.digest(new double[5], 600, 1e5, new double[5], new double[5]));
        assertSame(CorrosionDigest.EMPTY, s.digest(new double[]{-1, Double.NaN, 0, Double.NEGATIVE_INFINITY, 0}, 600, 1e5, null, null));
        double[] values = {Double.NaN, -1, 0, 1e-300, 1, 1e300, Double.MAX_VALUE, Double.POSITIVE_INFINITY};
        for (double x : values) for (double t : values) {
            double[] moles = {x, 1, x, 1, x};
            var d = s.digest(moles, t, x, moles, moles);
            for (double v : new double[]{d.temperature(), d.sulfur(), d.acid(), d.h2s(), d.co2(), d.so2(), d.nh3(), d.o2(), d.h2(), d.fouling()})
                assertTrue(Double.isFinite(v) && v >= 0, x + " " + t + ": " + d);
        }
        assertThrows(IllegalArgumentException.class, () -> s.digest(new double[4], 300, 1e5, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CorrosionStandard(MOLAR, FORMULAS, new double[4], new double[5], new double[5]));
        assertThrows(IllegalArgumentException.class, () -> new CorrosionStandard(new double[]{0, 1, 1, 1, 1}, FORMULAS, new double[5], new double[5], new double[5]));
        assertThrows(IllegalArgumentException.class, () -> new CorrosionStandard(MOLAR, FORMULAS, new double[]{-1, 0, 0, 0, 0}, new double[5], new double[5]));
    }
}

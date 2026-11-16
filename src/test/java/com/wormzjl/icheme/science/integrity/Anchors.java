package com.wormzjl.icheme.science.integrity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The anchor checks of the integrity tests (batch 2026-11-16-fouling-corrosion, WP1). */
final class Anchors {
    private Anchors() {}

    static final double K = 273.15;

    /** A number the plan prints, to 1 %. */
    static void plan(double printed, double actual, String what) {
        assertEquals(printed, actual, .01 * Math.abs(printed), what);
    }

    /**
     * A number where the plan's rounded value and the formula differ by more than 1 %: the formula's value (computed
     * independently), to 1e-6; the plan's printed value is named in {@code what}.
     */
    static void formula(double exact, double actual, String what) {
        assertEquals(exact, actual, 1e-6 * Math.abs(exact), what);
    }

    /** A digest with only the named fields set. */
    static CorrosionDigest digest(double celsius, boolean wet, boolean oil, double sulfur, double acid, double h2s, double co2,
                                  double so2, double nh3, double o2, double h2) {
        return new CorrosionDigest(celsius + K, wet, oil, sulfur, acid, h2s, co2, so2, nh3, o2, h2, 0);
    }
}

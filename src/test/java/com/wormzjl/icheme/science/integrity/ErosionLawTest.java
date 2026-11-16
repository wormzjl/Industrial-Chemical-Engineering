package com.wormzjl.icheme.science.integrity;

import org.junit.jupiter.api.Test;

import static com.wormzjl.icheme.science.integrity.Anchors.*;
import static com.wormzjl.icheme.science.integrity.Metallurgy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan sections 3.3 and 8: erosion of walls, trims and pumps. */
class ErosionLawTest {
    private static final double YEAR = 20;

    /** Process hours a new trim lasts at {@code share} and a drop of {@code bar} on a liquid of 800 kg/m³. */
    private static double trimHours(double share, double bar) {
        return 1 / ErosionLaw.trim(share, ErosionLaw.trimSpeed(bar * 1e5, 800), YEAR) / 3600;
    }

    @Test
    void wallAnchors() {
        formula(.015229231509727027, ErosionLaw.wall(CARBON_STEEL, .01, 2), "1 wt% at 2 m/s (the plan prints 0.015)");
        formula(.16493848884661177, ErosionLaw.wall(CARBON_STEEL, .01, 5), "1 wt% at 5 m/s (the plan prints 0.16)");
        assertEquals(1, ErosionLaw.wall(CARBON_STEEL, .01, 10), 1e-12, "the reference");
        double[] factor = {1, .8, .5, .4};
        for (var m : values()) assertEquals(factor[m.ordinal()] * ErosionLaw.wall(CARBON_STEEL, .02, 3), ErosionLaw.wall(m, .02, 3), 1e-15, m.name());
        // Section 3.3: relative loss at the same solids against a tube at 2 m/s.
        double tube = ErosionLaw.wall(CARBON_STEEL, .01, 2);
        formula(222.86094420380783, ErosionLaw.wall(CARBON_STEEL, .01, 16) / tube, "a trim at 16 m/s (the plan prints 220)");
        plan(1700, ErosionLaw.wall(CARBON_STEEL, .01, 35) / tube, "a trim at 35 m/s");
        formula(1142.450188197167, ErosionLaw.wall(CARBON_STEEL, .01, 30) / tube, "an impeller tip at 30 m/s (the plan prints 1 100)");
    }

    @Test
    void trimAnchors() {
        formula(7.0710678118654755, ErosionLaw.trimSpeed(.2e5, 800), "0.2 bar (the plan prints 7)");
        formula(15.811388300841896, ErosionLaw.trimSpeed(1e5, 800), "1 bar (the plan prints 16)");
        formula(35.35533905932738, ErosionLaw.trimSpeed(5e5, 800), "5 bar (the plan prints 35)");
        plan(71, ErosionLaw.trimSpeed(20e5, 800), "20 bar");
        plan(860, trimHours(.01, .2), "0.2 bar, 1 wt%");
        plan(105, trimHours(.01, 1), "1 bar, 1 wt%");
        plan(13, trimHours(.01, 5), "5 bar, 1 wt%");
        formula(2.152208054086233, trimHours(.01, 20), "20 bar, 1 wt% (the plan prints 2.1)");
        plan(172, trimHours(.05, .2), "0.2 bar, 5 wt%");
        plan(21, trimHours(.05, 1), "1 bar, 5 wt%");
        formula(2.6097099217244883, trimHours(.05, 5), "5 bar, 5 wt% (the plan prints 2.7)");
        formula(.43044161081724663, trimHours(.05, 20), "20 bar, 5 wt% (the plan prints 0.4)");
        assertEquals(ErosionLaw.trim(.01, 16, 20) / 2, ErosionLaw.trim(.01, 16, 40), 1e-20, "the year scales the rate");
    }

    @Test
    void thePumpsSolidsIntensity() {
        plan(.18, ErosionLaw.solidsIntensity(.01), "1 wt%");
        formula(8 / .18, 8 / ErosionLaw.solidsIntensity(.01), "process hours at the default erosion life (the plan prints 44)");
        assertEquals(0, ErosionLaw.solidsIntensity(.001));
        assertEquals(1, ErosionLaw.solidsIntensity(.051), 1e-12);
        assertEquals(1, ErosionLaw.solidsIntensity(1));
    }

    @Test
    void erosionRisesWithShareAndSpeed() {
        for (double v = .1; v < 200; v *= 1.3)
            for (double phi = 1e-4; phi < 1; phi *= 1.7) {
                for (var m : values()) {
                    double faster = ErosionLaw.wall(m, phi, v * 1.3);
                    assertTrue(faster > ErosionLaw.wall(m, phi, v) || faster == CorrosionLaw.MAX_RATE, m + " speed " + v);
                    assertTrue(ErosionLaw.wall(m, phi * 1.7, v) >= ErosionLaw.wall(m, phi, v), m + " share " + phi);
                }
                assertTrue(ErosionLaw.trim(phi, v * 1.3, YEAR) > ErosionLaw.trim(phi, v, YEAR));
                assertTrue(ErosionLaw.trim(phi * 1.7, v, YEAR) >= ErosionLaw.trim(phi, v, YEAR));
                assertTrue(ErosionLaw.solidsIntensity(phi * 1.7) >= ErosionLaw.solidsIntensity(phi));
            }
        for (double dp = 1e3; dp < 1e8; dp *= 2) assertTrue(ErosionLaw.trimSpeed(dp * 2, 800) > ErosionLaw.trimSpeed(dp, 800));
    }

    @Test
    void nothingErodesWithoutShareOrSpeedAndNothingIsNaN() {
        double[] bad = {Double.NaN, 0, -1, Double.NEGATIVE_INFINITY};
        double[] any = {Double.NaN, -1, 0, 1e-9, .01, 1, 50, 1e6, Double.MAX_VALUE, Double.POSITIVE_INFINITY};
        for (double b : bad) for (double x : any) {
            for (var m : values()) {
                assertEquals(0, ErosionLaw.wall(m, b, x), "share " + b);
                assertEquals(0, ErosionLaw.wall(m, x, b), "speed " + b);
            }
            assertEquals(0, ErosionLaw.trim(b, x, YEAR));
            assertEquals(0, ErosionLaw.trim(x, b, YEAR));
            assertEquals(0, ErosionLaw.trim(x, x, b), "year " + b);
            assertEquals(0, ErosionLaw.trimSpeed(b, x));
            assertEquals(0, ErosionLaw.trimSpeed(x, b));
            assertEquals(0, ErosionLaw.solidsIntensity(b));
        }
        for (double x : any) for (double y : any) {
            for (var m : values()) assertFinite(ErosionLaw.wall(m, x, y));
            assertFinite(ErosionLaw.trim(x, y, YEAR));
            assertFinite(ErosionLaw.trimSpeed(x, y));
            assertFinite(ErosionLaw.solidsIntensity(x));
        }
    }

    private static void assertFinite(double r) {
        assertTrue(r >= 0 && Double.isFinite(r), "got " + r);
    }
}

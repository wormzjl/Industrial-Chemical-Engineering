package com.wormzjl.icheme.science.integrity;

import org.junit.jupiter.api.Test;

import static com.wormzjl.icheme.science.integrity.Anchors.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan sections 6.5, 7 and 12: the fouling placeholder and the wall's stages and lives. */
class FoulingAndWallTest {
    private static CorrosionDigest side(double celsius, boolean wet, boolean oil, double fouling) {
        return new CorrosionDigest(celsius + K, wet, oil, 0, 0, 0, 0, 0, 0, 0, 0, fouling);
    }

    /**
     * The game's end values are half the TEMA anchors (D14, 2026-11-16); before D14 the end value was the anchor itself
     * (0.00035, 0.0007, 0.0005, 0.0009, 0.0018, 0.0002, 0.00085, the cap 0.002), each row exactly twice the value it asserts now.
     */
    @Test
    void theAsymptoteFollowsThePlansTable() {
        assertEquals(0, FoulingLaw.asymptote(side(200, false, false, .0005), 0), "gas and steam");
        assertEquals(0, FoulingLaw.asymptote(side(200, false, false, 0), .05), "gas carries no solids");
        assertEquals(0, FoulingLaw.asymptote(side(100, false, true, 0), 0), "a light clean liquid");
        assertEquals(.000175, FoulingLaw.asymptote(side(30, true, false, 0), 0), "water (0.00035 before D14)");
        assertEquals(.000175, FoulingLaw.asymptote(side(50, true, false, 0), 0), "water at 50 °C (0.00035 before D14)");
        assertEquals(.00035, FoulingLaw.asymptote(side(51, true, false, 0), 0), "water above 50 °C (0.0007 before D14)");
        assertEquals(.00025, FoulingLaw.asymptote(side(200, false, true, .0005), 0), 1e-15, "crude below 260 °C (0.0005 before D14)");
        assertEquals(.00045, FoulingLaw.asymptote(side(260, false, true, .0005), 0), 1e-15, "crude from 260 °C (0.0009 before D14)");
        assertEquals(.0009, FoulingLaw.asymptote(side(200, false, true, .0018), 0), 1e-15, "residue (0.0018 before D14)");
        assertEquals(.0009, FoulingLaw.asymptote(side(350, false, true, .0018), 0), 1e-15, "residue, hot (0.0018 before D14)");
        assertEquals(.00025, FoulingLaw.asymptote(side(200, true, true, .0005), 0), 1e-15, "oil with free water reads the oil (0.0005 before D14)");
        assertEquals(.0001, FoulingLaw.asymptote(side(30, false, true, 0), .01), 1e-15, "1 wt% solids adds 0.0001 (0.0002 before D14)");
        assertEquals(.000175 + .00025, FoulingLaw.asymptote(side(30, true, false, 0), .5), 1e-15, "at most + 0.00025 (0.00035 + 0.0005 before D14)");
        assertEquals(.00115, FoulingLaw.asymptote(side(350, false, true, .0018), .1), 1e-15, "the highest end value, hot residue with solids: (0.0018 + 0.0005) / 2; the cap 0.002 before D14");
        for (double f = 0; f <= .002; f += 1e-5) {
            assertTrue(FoulingLaw.asymptote(side(300, false, true, f + 1e-5), 0) >= FoulingLaw.asymptote(side(300, false, true, f), 0), "monotone in the number");
            assertTrue(FoulingLaw.asymptote(side(300, false, true, f), 0) >= FoulingLaw.asymptote(side(200, false, true, f), 0), "hot fouls more");
        }
        for (double x : new double[]{Double.NaN, -1, Double.POSITIVE_INFINITY}) {
            double r = FoulingLaw.asymptote(new CorrosionDigest(x, true, true, x, x, x, x, x, x, x, x, x), x);
            assertTrue(r >= 0 && r <= FoulingLaw.CAP, x + ": " + r);
        }
    }

    /** D14: the end value is {@link FoulingLaw#PLACEHOLDER_SHARE} times its TEMA anchor, and a side reaches it in a few steps. */
    @Test
    void theEndValueIsHalfTheAnchor() {
        assertEquals(.5, FoulingLaw.PLACEHOLDER_SHARE);
        double h = FoulingLaw.PLACEHOLDER_SHARE;
        assertEquals(h * FoulingLaw.WATER, FoulingLaw.asymptote(side(30, true, false, 0), 0), "water");
        assertEquals(h * FoulingLaw.WARM_WATER, FoulingLaw.asymptote(side(60, true, false, 0), 0), "warm water");
        assertEquals(h * .0005, FoulingLaw.asymptote(side(200, false, true, .0005), 0), 1e-18, "crude below 260 °C");
        assertEquals(h * FoulingLaw.HOT_CRUDE, FoulingLaw.asymptote(side(300, false, true, .0005), 0), 1e-18, "crude from 260 °C");
        assertEquals(h * .0018, FoulingLaw.asymptote(side(200, false, true, .0018), 0), 1e-18, "residue");
        assertEquals(h * (FoulingLaw.WATER + FoulingLaw.SOLIDS_PER_SHARE * .01), FoulingLaw.asymptote(side(30, true, false, 0), .01), 1e-18, "water with 1 wt% solids");
        assertEquals(h * (.0005 + FoulingLaw.SOLIDS_CAP), FoulingLaw.asymptote(side(200, false, true, .0005), .5), 1e-18, "crude with the solids' cap");
        assertEquals(3, FoulingLaw.level(FoulingLaw.asymptote(side(30, true, false, 0), 0)), "water 0.000175: 3 steps of 5e-5");
        assertEquals(5, FoulingLaw.level(FoulingLaw.asymptote(side(200, false, true, .0005), 0)), "crude 0.00025: 5 steps");
    }

    @Test
    void theDepositApproachesItsAsymptoteExactly() {
        double tau = 10, a = .0009;
        assertEquals(a * (1 - Math.exp(-1)), FoulingLaw.step(0, a, 10 * 3600, tau), 1e-15, "one time constant");
        double rf = 0;
        for (int i = 0; i < 10; i++) rf = FoulingLaw.step(rf, a, 3600, tau);
        assertEquals(FoulingLaw.step(0, a, 36000, tau), rf, 1e-15, "steps compose");
        assertEquals(.001, FoulingLaw.step(.001, a, 0, tau), "no time");
        assertEquals(.001, FoulingLaw.step(.001, a, Double.NaN, tau));
        assertEquals(.001, FoulingLaw.step(.001, a, 100, Double.NaN));
        assertEquals(a, FoulingLaw.step(.001, a, 100, 0), "no time constant");
        assertTrue(FoulingLaw.step(.0015, .0005, 3600, tau) < .0015, "a cleaner stream lowers it");
        for (double x : new double[]{Double.NaN, -1, 0, .0001, .002, 1, Double.POSITIVE_INFINITY})
            for (double y : new double[]{Double.NaN, -1, 0, .0009, 1, Double.POSITIVE_INFINITY}) {
                double r = FoulingLaw.step(x, y, 3600, tau);
                assertTrue(r >= 0 && r <= FoulingLaw.CAP, x + " to " + y + ": " + r);
            }
    }

    /** Whole steps of 5e-5 m²K/W since D14 (1e-4 before: 0.99e-4 was 0, 0.0003 was 3, the cap 20; each level now twice). */
    @Test
    void levelsAndThickness() {
        assertEquals(0, FoulingLaw.level(0));
        assertEquals(0, FoulingLaw.level(.49e-4));
        assertEquals(1, FoulingLaw.level(5e-5));
        assertEquals(1, FoulingLaw.level(.99e-4), "0 before D14");
        assertEquals(6, FoulingLaw.level(.0003), "3 before D14");
        assertEquals(40, FoulingLaw.level(.002), "20 before D14");
        assertEquals(40, FoulingLaw.level(5), "20 before D14");
        assertEquals(0, FoulingLaw.level(Double.NaN));
        assertEquals(0, FoulingLaw.level(-1));
        // Section 3.2: delta = Rf k_d.
        assertEquals(.0002, FoulingLaw.thickness(.0002), 1e-18, "0.2 mm");
        assertEquals(.002, FoulingLaw.thickness(.01), "the cap");
        assertEquals(0, FoulingLaw.thickness(Double.NaN));
    }

    @Test
    void wallStages() {
        assertEquals(WallState.Stage.SOUND, WallState.stage(0, 3));
        assertEquals(WallState.Stage.SOUND, WallState.stage(1.49, 3));
        assertEquals(WallState.Stage.THIN, WallState.stage(1.5, 3));
        assertEquals(WallState.Stage.THIN, WallState.stage(2.69, 3));
        assertEquals(WallState.Stage.WEEPING, WallState.stage(2.71, 3));
        assertEquals(WallState.Stage.THROUGH, WallState.stage(3, 3));
        assertEquals(WallState.Stage.THROUGH, WallState.stage(5, 1.5));
        assertEquals(WallState.Stage.SOUND, WallState.stage(Double.NaN, 3));
        assertEquals(WallState.Stage.SOUND, WallState.stage(-1, 3));
        assertEquals(WallState.Stage.THROUGH, WallState.stage(.1, 0), "no allowance");
    }

    /** Section 6.5: a 3 mm carbon steel wall at a 20 h year. */
    @Test
    void wallLives() {
        double[] rate = {.1, .3, 1.3, 5}, hours = {600, 200, 46, 12};
        for (int i = 0; i < rate.length; i++)
            plan(hours[i], WallState.lifeProcessSeconds(0, 3, rate[i], 20) / 3600, rate[i] + " mm/y");
        assertEquals(300 * 3600, WallState.lifeProcessSeconds(1.5, 3, .1, 20), 1e-6, "half used");
        assertEquals(Double.POSITIVE_INFINITY, WallState.lifeProcessSeconds(0, 3, 0, 20));
        assertEquals(Double.POSITIVE_INFINITY, WallState.lifeProcessSeconds(0, 3, Double.NaN, 20));
        assertEquals(0, WallState.lifeProcessSeconds(3, 3, 1, 20), "through");
        assertEquals(0, WallState.lifeProcessSeconds(Double.NaN, Double.NaN, 1, 20));
    }
}

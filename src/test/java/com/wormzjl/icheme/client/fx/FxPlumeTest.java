package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mixing-line excess against CUSTOM_SHADER_SYSTEM_PLAN.md section 2's table, in g water per kg dry air. */
class FxPlumeTest {
    /** Tower exhaust at the calibration point: saturated at 28.51 °C. */
    private static final double TOWER = 28.51, TOWER_W = FxPlume.saturationRatio(TOWER);
    /** Gas-fired heater stack: 180 °C flue, 12 % water by mass of dry gas. */
    private static final double STACK = 180, STACK_W = .1217;

    private static double grams(double exhaust, double w, double ambient, double rh) {
        return 1000 * FxPlume.excess(exhaust, w, ambient, rh);
    }

    @Test
    void saturationMatchesBuck() {
        assertEquals(3169.2, FxPlume.saturationPressure(25), 2);
        assertEquals(611.21, FxPlume.saturationPressure(0), 1e-9);
        assertEquals(.0249, TOWER_W, 2e-4);
    }

    @Test
    void humidityRatioGuardsTheBoilingPoint() {
        assertEquals(Double.POSITIVE_INFINITY, FxPlume.humidityRatio(FxPlume.PRESSURE, FxPlume.PRESSURE));
        assertEquals(Double.POSITIVE_INFINITY, FxPlume.saturationRatio(120));
    }

    @Test
    void towerAtTheModsAmbientShowsNoPlume() {
        assertTrue(grams(TOWER, TOWER_W, 25, .5) <= 1e-3);
        assertTrue(Double.isNaN(FxPlume.visibleEnd(TOWER, TOWER_W, 25, .5)) || FxPlume.visibleEnd(TOWER, TOWER_W, 25, .5) > .99);
    }

    @Test
    void towerPlumeGrowsInColdHumidAir() {
        assertEquals(.28, grams(TOWER, TOWER_W, 15, .7), .02);
        assertEquals(2.76, grams(TOWER, TOWER_W, 5, .8), .05);
        double cool = FxPlume.visibleEnd(TOWER, TOWER_W, 15, .7), cold = FxPlume.visibleEnd(TOWER, TOWER_W, 5, .8);
        assertTrue(cold < cool, "a colder day carries the plume to a further dilution");
        assertTrue(FxPlume.excessAt(cold * 1.01, TOWER, TOWER_W, 5, FxPlume.ambientRatio(5, .8)) > 0);
        assertTrue(FxPlume.excessAt(cold * .99, TOWER, TOWER_W, 5, FxPlume.ambientRatio(5, .8)) <= 0);
    }

    @Test
    void gasStackShowsOnlyInTheCold() {
        assertEquals(.38, grams(STACK, STACK_W, 5, .8), .03);
        assertTrue(grams(STACK, STACK_W, 25, .5) < 0);
    }

    @Test
    void visibleLengthFollowsTheJetDilution() {
        assertEquals(0, FxPlume.visibleLength(3, Double.NaN, 200));
        assertEquals(200, FxPlume.visibleLength(3, 0, 200));
        assertEquals(5 * 3 / .5, FxPlume.visibleLength(3, .5, 200), 1e-9);
    }
}

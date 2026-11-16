package com.wormzjl.icheme.science.integrity;

import org.junit.jupiter.api.Test;

import static com.wormzjl.icheme.science.integrity.Anchors.*;
import static com.wormzjl.icheme.science.integrity.Metallurgy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan section 5 and table 6.3: the four materials, their limits and factors. */
class MetallurgyTest {
    @Test
    void theFourMaterialsCarryThePlansNumbers() {
        assertArrayEquals(new Metallurgy[]{CARBON_STEEL, CHROME_STEEL, STAINLESS_STEEL, NICKEL_ALLOY}, values(), "the order is saved by name; append-only");
        double[] allowance = {3, 3, 1.5, 1.5}, limit = {540, 650, 815, 980}, cost = {1, 2, 4, 15};
        for (var m : values()) {
            assertEquals(allowance[m.ordinal()], m.allowance(), m.name());
            assertEquals(limit[m.ordinal()] + K, m.limit(), 1e-9, m.name());
            assertEquals(cost[m.ordinal()], m.cost(), m.name());
        }
    }

    @Test
    void byNameReadsEveryConstantAndDefaultsToCarbonSteel() {
        for (var m : values()) assertSame(m, byName(m.name()));
        assertSame(CARBON_STEEL, byName(null));
        assertSame(CARBON_STEEL, byName("UNOBTAINIUM"));
        assertSame(CARBON_STEEL, byName("stainless_steel"), "names are exact");
    }

    /** The hot-hydrogen line, at the plan's pressures, in °C. */
    @Test
    void theHydrogenLimitFallsWithPressureToItsFloor() {
        double[] bar = {7, 30, 100, 200};
        double[] carbon = {260, 232, 208, 205}, chrome = {600, 524, 461, 450};
        for (int i = 0; i < bar.length; i++) {
            plan(carbon[i], CARBON_STEEL.hydrogenLimit(bar[i]) - K, "carbon steel at " + bar[i] + " bar");
            plan(chrome[i], CHROME_STEEL.hydrogenLimit(bar[i]) - K, "chrome steel at " + bar[i] + " bar");
        }
        for (var m : values()) {
            assertEquals(Double.POSITIVE_INFINITY, m.hydrogenLimit(6.99), m.name() + " below 7 bar");
            assertEquals(Double.POSITIVE_INFINITY, m.hydrogenLimit(Double.NaN), m.name());
            assertEquals(Double.POSITIVE_INFINITY, m.hydrogenLimit(-1), m.name());
        }
        for (double p : new double[]{7, 30, 100, 1e4}) {
            assertEquals(Double.POSITIVE_INFINITY, STAINLESS_STEEL.hydrogenLimit(p));
            assertEquals(Double.POSITIVE_INFINITY, NICKEL_ALLOY.hydrogenLimit(p));
        }
        for (double p = 7; p < 500; p += 1) {
            assertTrue(CARBON_STEEL.hydrogenLimit(p + 1) <= CARBON_STEEL.hydrogenLimit(p), "falls with pressure");
            assertTrue(CHROME_STEEL.hydrogenLimit(p) > CARBON_STEEL.hydrogenLimit(p), "chrome steel stands more");
        }
    }

    /** The oxygen exemption pressures: p_150 up to 150 °C, halved per 100 K above, floor 0.3 bar. */
    @Test
    void theOxygenLimitHalvesPer100KelvinAbove150Celsius() {
        double[] p150 = {2, 2, 14, 69};
        for (var m : values()) {
            assertEquals(p150[m.ordinal()], m.oxygenLimit(20 + K), m.name());
            assertEquals(p150[m.ordinal()], m.oxygenLimit(150 + K), m.name());
            assertEquals(p150[m.ordinal()], m.oxygenLimit(Double.NaN), m.name());
        }
        plan(1, CARBON_STEEL.oxygenLimit(250 + K), "carbon steel at 250 °C");
        plan(.5, CARBON_STEEL.oxygenLimit(350 + K), "carbon steel at 350 °C");
        plan(1, CHROME_STEEL.oxygenLimit(250 + K), "chrome steel at 250 °C");
        plan(7, STAINLESS_STEEL.oxygenLimit(250 + K), "stainless at 250 °C");
        plan(3.5, STAINLESS_STEEL.oxygenLimit(350 + K), "stainless at 350 °C");
        formula(34.5, NICKEL_ALLOY.oxygenLimit(250 + K), "nickel alloy at 250 °C (the plan prints 35)");
        formula(17.25, NICKEL_ALLOY.oxygenLimit(350 + K), "nickel alloy at 350 °C (the plan prints 17)");
        for (var m : values()) assertEquals(OXYGEN_FLOOR_BAR, m.oxygenLimit(5000), m.name() + ": the floor keeps air free");
    }

    /** Plan table 6.3, every cell. */
    @Test
    void everyFactorOfTheTable() {
        Object[][] rows = {
                {Mechanism.SULFIDATION, false, 1, .06, .01, .01},
                {Mechanism.SULFIDATION, true, 1, .6, .01, .01},
                {Mechanism.NAPHTHENIC_ACID, false, 1, .6, .02, .01},
                {Mechanism.SOUR_WATER, false, 1, 1, .01, .005},
                {Mechanism.WET_CO2, false, 1, .3, .005, .002},
                {Mechanism.AMMONIUM_BISULFIDE, false, 1, 1, .2, .02},
                {Mechanism.ACID_CONDENSATE, false, 1, 1, .1, .02},
                {Mechanism.AERATED_WATER, false, 1, .6, .005, .002},
                {Mechanism.CORROSIVE_AIR, false, 1, .6, .01, .005},
                {Mechanism.EROSION, false, 1, .8, .5, .4}};
        for (var row : rows) for (var m : values()) {
            var mechanism = (Mechanism) row[0];
            double expected = ((Number) row[2 + m.ordinal()]).doubleValue();
            assertEquals(expected, m.factor(mechanism, (Boolean) row[1]), mechanism + " " + row[1] + " " + m);
            if (mechanism != Mechanism.SULFIDATION) assertEquals(expected, m.factor(mechanism, !(Boolean) row[1]), "the flag moves sulfidation only");
        }
        for (var m : values()) for (var own : new Mechanism[]{Mechanism.OVERHEATING, Mechanism.HYDROGEN_ATTACK, Mechanism.OXYGEN_FIRE})
            assertEquals(1, m.factor(own, false), own + " is the material's own law");
    }
}

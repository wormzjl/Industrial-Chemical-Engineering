package com.wormzjl.icheme.science.integrity;

import org.junit.jupiter.api.Test;

import static com.wormzjl.icheme.science.integrity.Anchors.*;
import static com.wormzjl.icheme.science.integrity.Metallurgy.*;
import static org.junit.jupiter.api.Assertions.*;

/** Plan sections 3.1 and 6.2: the corrosion rates, their anchors, monotony and guards. */
class CorrosionLawTest {
    private static CorrosionDigest oil(double celsius, double sulfur, double acid) {
        return digest(celsius, false, true, sulfur, acid, 0, 0, 0, 0, 0, 0);
    }

    private static CorrosionDigest water(double celsius, double h2s, double co2, double so2, double nh3, double o2) {
        return digest(celsius, true, false, 0, 0, h2s, co2, so2, nh3, o2, 0);
    }

    /** M1 on carbon steel at 0.6 wt%: 0.08 at 260 °C, 0.18 at 288 °C, 1.3 at 370 °C, 2.4 at 400 °C. */
    @Test
    void sulfidationAnchors() {
        double[] celsius = {260, 288, 370, 400}, rate = {.08, .18, 1.3, 2.4};
        for (int i = 0; i < celsius.length; i++)
            plan(rate[i], CorrosionLaw.rate(Mechanism.SULFIDATION, oil(celsius[i], .6, 0)), "M1 at " + celsius[i] + " °C");
        var crude = oil(370, .6, 0);
        var carbon = CorrosionLaw.inside(CARBON_STEEL, crude);
        assertEquals(Mechanism.SULFIDATION, carbon.mechanism());
        plan(1.3, carbon.rate(), "crude at 370 °C on carbon steel");
        plan(.156, CorrosionLaw.inside(CHROME_STEEL, crude).rate(), "on chrome steel (the plan's 3.1 prints 0.16)");
        plan(.013, CorrosionLaw.inside(STAINLESS_STEEL, crude).rate(), "on stainless");
        assertEquals(0, CorrosionLaw.rate(Mechanism.SULFIDATION, oil(229, .6, 0)), "below 503 K");
        assertEquals(0, CorrosionLaw.rate(Mechanism.SULFIDATION, oil(370, 0, 0)), "no sulfur");
        // H2S counts as 60 wt% per bar, the sulfur capped at 5 wt%.
        assertEquals(CorrosionLaw.rate(Mechanism.SULFIDATION, oil(370, .6, 0)),
                CorrosionLaw.rate(Mechanism.SULFIDATION, digest(370, false, false, 0, 0, .01, 0, 0, 0, 0, 0)), 1e-12);
        assertEquals(CorrosionLaw.rate(Mechanism.SULFIDATION, oil(370, 5, 0)), CorrosionLaw.rate(Mechanism.SULFIDATION, oil(370, 9, 0)));
    }

    /** Sections 3.1 and 6.2: naphthenic acid, sour water, wet CO2, aerated water. */
    @Test
    void wetAndAcidAnchors() {
        var heavy = oil(350, 0, 3);
        plan(1.5, CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, heavy), "acid number 3 at 350 °C");
        plan(.03, CorrosionLaw.inside(STAINLESS_STEEL, heavy).rate(), "on stainless");
        assertEquals(0, CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, oil(350, 0, .49)), "below acid number 0.5");
        assertEquals(0, CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, oil(401, 0, 3)), "above 673 K");
        var sour = water(60, 1, 0, 0, 0, 0);
        plan(.3, CorrosionLaw.rate(Mechanism.SOUR_WATER, sour), "sour water at 1 bar");
        plan(.003, CorrosionLaw.inside(STAINLESS_STEEL, sour).rate(), "on stainless");
        assertEquals(0, CorrosionLaw.rate(Mechanism.SOUR_WATER, water(150, 1, 0, 0, 0, 0)), "from 423 K");
        var co2 = water(60, 0, 1, 0, 0, 0);
        plan(1.4, CorrosionLaw.rate(Mechanism.WET_CO2, co2), "wet CO2 at 1 bar, 60 °C");
        plan(.007, CorrosionLaw.inside(STAINLESS_STEEL, co2).rate(), "on stainless");
        formula(.316073794730484, CorrosionLaw.rate(Mechanism.AERATED_WATER, water(35, 0, 0, 0, 0, .21)),
                "open cooling water at 35 °C (the plan prints 0.3)");
        assertEquals(2 * Math.sqrt(.1), CorrosionLaw.rate(Mechanism.AMMONIUM_BISULFIDE, water(40, .1, 0, 0, 1, 0)), 1e-12);
        assertEquals(Math.sqrt(10), CorrosionLaw.rate(Mechanism.ACID_CONDENSATE, water(40, 0, 0, .01, 0, 0)), 1e-12);
        for (var m : new Mechanism[]{Mechanism.SOUR_WATER, Mechanism.WET_CO2, Mechanism.AMMONIUM_BISULFIDE, Mechanism.ACID_CONDENSATE, Mechanism.AERATED_WATER})
            assertEquals(0, CorrosionLaw.rate(m, digest(60, false, false, 0, 0, 1, 1, 1, 1, 1, 0)), m + " needs free water");
        assertEquals(0, CorrosionLaw.rate(Mechanism.OVERHEATING, water(60, 1, 1, 1, 1, 1)), "rate() is M1 to M7 only");
    }

    /** M8, M10, M11 on the material itself; 3.1's hydrogen and oxygen rows. */
    @Test
    void theMaterialsOwnLimits() {
        var h30at250 = digest(250, false, false, 0, 0, 0, 0, 0, 0, 0, 30);
        var h30at300 = digest(300, false, false, 0, 0, 0, 0, 0, 0, 0, 30);
        formula(.8337240088717347, CorrosionLaw.inside(CARBON_STEEL, h30at250).rate(), "carbon steel, 30 bar H2, 250 °C (the plan prints 0.8)");
        formula(3.3348960354869392, CorrosionLaw.inside(CARBON_STEEL, h30at300).rate(), "carbon steel, 30 bar H2, 300 °C (the plan prints 3.3)");
        assertEquals(Mechanism.HYDROGEN_ATTACK, CorrosionLaw.inside(CARBON_STEEL, h30at300).mechanism());
        plan(1.6, CorrosionLaw.inside(CARBON_STEEL, digest(250, false, false, 0, 0, 0, 0, 0, 0, 0, 100)).rate(), "100 bar H2 at 250 °C");
        assertTrue(3 / CorrosionLaw.inside(CARBON_STEEL, h30at300).rate() < 1, "under a year");
        assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(CHROME_STEEL, h30at300), "chrome steel to 524 °C at 30 bar");
        assertEquals(0, CorrosionLaw.hydrogenAttack(CHROME_STEEL, 524 + K, 30));
        assertTrue(CorrosionLaw.hydrogenAttack(CHROME_STEEL, 525 + K, 30) > 0);
        assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(STAINLESS_STEEL, digest(700, false, false, 0, 0, 0, 0, 0, 0, 0, 300)));
        assertEquals(0, CorrosionLaw.hydrogenAttack(CARBON_STEEL, 400 + K, 6.9), "below 7 bar");
        // Hydrogen with H2S at 370 °C: sulfidation at the hydrogen factor on chrome steel.
        var hydrotreater = digest(370, false, false, 0, 0, .01, 0, 0, 0, 0, 30);
        assertTrue(CorrosionLaw.inside(CARBON_STEEL, hydrotreater).rate() >= 1.3, "1.3 mm/y or more on carbon steel");
        formula(1.3041078637325965 * .6, CorrosionLaw.inside(CHROME_STEEL, hydrotreater).rate(), "chrome steel (the plan prints 0.8)");
        assertEquals(Mechanism.SULFIDATION, CorrosionLaw.inside(CHROME_STEEL, hydrotreater).mechanism());
        plan(.013, CorrosionLaw.inside(STAINLESS_STEEL, hydrotreater).rate(), "stainless");
        // Oxygen at 10 bar burns carbon steel through in minutes; stainless stands to 14 bar, nickel alloy to 69.
        var oxygen = digest(20, false, false, 0, 0, 0, 0, 0, 0, 10, 0);
        var fire = CorrosionLaw.inside(CARBON_STEEL, oxygen);
        assertEquals(Mechanism.OXYGEN_FIRE, fire.mechanism());
        assertEquals(250, fire.rate(), 1e-9);
        assertTrue(WallState.lifeProcessSeconds(0, 3, fire.rate(), 20) < 15 * 60, "minutes of process time");
        assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(STAINLESS_STEEL, oxygen));
        assertEquals(0, CorrosionLaw.oxygenFire(STAINLESS_STEEL, 20 + K, 14));
        assertTrue(CorrosionLaw.oxygenFire(STAINLESS_STEEL, 20 + K, 14.1) > 0);
        assertEquals(0, CorrosionLaw.oxygenFire(NICKEL_ALLOY, 20 + K, 69));
        assertEquals(0, CorrosionLaw.oxygenFire(CARBON_STEEL, 1000 + K, .21), "air at one atmosphere never burns");
        // Overheating above the limit.
        assertEquals(0, CorrosionLaw.overheating(CARBON_STEEL, CARBON_STEEL.limit()));
        assertEquals(.5, CorrosionLaw.overheating(CARBON_STEEL, CARBON_STEEL.limit() + 25), 1e-12);
        assertEquals(Mechanism.OVERHEATING, CorrosionLaw.inside(CARBON_STEEL, digest(600, false, false, 0, 0, 0, 0, 0, 0, 0, 0)).mechanism());
        assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(NICKEL_ALLOY, digest(600, false, false, 0, 0, 0, 0, 0, 0, 0, 0)));
    }

    /** Section 3.1's lives: 2.3 years at 1.3 mm/y, 2 at 1.5, 10 at 0.3; dry gas and clean liquid 0. */
    @Test
    void cleanServiceIsFreeAndLivesFollow() {
        plan(2.3, CARBON_STEEL.allowance() / 1.3041078637325965, "2.3 years");
        plan(2, CARBON_STEEL.allowance() / CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, oil(350, 0, 3)), "2 years");
        plan(10, CARBON_STEEL.allowance() / CorrosionLaw.rate(Mechanism.SOUR_WATER, water(60, 1, 0, 0, 0, 0)), "10 years");
        for (double c : new double[]{20, 150, 300, 500})
            for (var d : new CorrosionDigest[]{digest(c, false, false, 0, 0, 0, 0, 0, 0, 0, 0), oil(c, 0, 0), water(c, 0, 0, 0, 0, 0)})
                assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(CARBON_STEEL, d), c + " °C");
        assertEquals(0, CorrosionLaw.outside(CARBON_STEEL, .0099));
        assertEquals(.005, CorrosionLaw.outside(CARBON_STEEL, .01), 1e-15);
        assertEquals(2.5, CorrosionLaw.outside(CARBON_STEEL, 50));
        assertEquals(2.5 * .6, CorrosionLaw.outside(CHROME_STEEL, 5), 1e-12);
        assertEquals(2.5 * .005, CorrosionLaw.outside(NICKEL_ALLOY, 5), 1e-12);
    }

    @Test
    void ratesRiseWithTemperatureAndPressure() {
        for (double c = 230; c < 500; c += 5) {
            assertTrue(CorrosionLaw.rate(Mechanism.SULFIDATION, oil(c + 5, .6, 0)) >= CorrosionLaw.rate(Mechanism.SULFIDATION, oil(c, .6, 0)), c + " °C");
            assertTrue(CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, oil(c + 5, 0, 3)) >= CorrosionLaw.rate(Mechanism.NAPHTHENIC_ACID, oil(c, 0, 3))
                    || c + 5 + K > CorrosionLaw.ACID_TO, c + " °C: rises up to the window's top");
        }
        for (double c = 0; c < 150; c += 5) {
            assertTrue(CorrosionLaw.rate(Mechanism.WET_CO2, water(c + 5, 0, 1, 0, 0, 0)) >= CorrosionLaw.rate(Mechanism.WET_CO2, water(c, 0, 1, 0, 0, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.AERATED_WATER, water(c + 5, 0, 0, 0, 0, .21)) >= CorrosionLaw.rate(Mechanism.AERATED_WATER, water(c, 0, 0, 0, 0, .21)));
        }
        for (double c = 500; c < 1200; c += 10) for (var m : values()) {
            assertTrue(CorrosionLaw.overheating(m, c + 10 + K) >= CorrosionLaw.overheating(m, c + K));
            assertTrue(CorrosionLaw.hydrogenAttack(m, c + 10 + K, 30) >= CorrosionLaw.hydrogenAttack(m, c + K, 30));
        }
        for (double p = .001; p < 50; p *= 1.5) {
            double q = p * 1.5;
            assertTrue(CorrosionLaw.rate(Mechanism.SULFIDATION, digest(370, false, false, 0, 0, q, 0, 0, 0, 0, 0)) >= CorrosionLaw.rate(Mechanism.SULFIDATION, digest(370, false, false, 0, 0, p, 0, 0, 0, 0, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.SOUR_WATER, water(60, q, 0, 0, 0, 0)) >= CorrosionLaw.rate(Mechanism.SOUR_WATER, water(60, p, 0, 0, 0, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.WET_CO2, water(60, 0, q, 0, 0, 0)) >= CorrosionLaw.rate(Mechanism.WET_CO2, water(60, 0, p, 0, 0, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.AMMONIUM_BISULFIDE, water(60, q, 0, 0, q, 0)) >= CorrosionLaw.rate(Mechanism.AMMONIUM_BISULFIDE, water(60, p, 0, 0, p, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.ACID_CONDENSATE, water(60, 0, 0, q, 0, 0)) >= CorrosionLaw.rate(Mechanism.ACID_CONDENSATE, water(60, 0, 0, p, 0, 0)));
            assertTrue(CorrosionLaw.rate(Mechanism.AERATED_WATER, water(60, 0, 0, 0, 0, q)) >= CorrosionLaw.rate(Mechanism.AERATED_WATER, water(60, 0, 0, 0, 0, p)));
            assertTrue(CorrosionLaw.hydrogenAttack(CARBON_STEEL, 300 + K, q + 7) >= CorrosionLaw.hydrogenAttack(CARBON_STEEL, 300 + K, p + 7));
            assertTrue(CorrosionLaw.oxygenFire(CARBON_STEEL, 20 + K, q) >= CorrosionLaw.oxygenFire(CARBON_STEEL, 20 + K, p));
        }
    }

    /** No NaN for NaN, zero, negative or extreme inputs; every rate finite and at most the cap. */
    @Test
    void everyRateIsFiniteAndNotNegative() {
        double[] values = {Double.NaN, -1, 0, 1e-300, .5, 7, 1e6, Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[] temperatures = {Double.NaN, -10, 0, 1, 300, 650, 1e4, 1e300, Double.POSITIVE_INFINITY};
        for (double t : temperatures) for (double x : values) for (boolean wet : new boolean[]{false, true}) {
            var d = new CorrosionDigest(t, wet, !wet, x, x, x, x, x, x, x, x, x);
            for (var m : Mechanism.values()) check(CorrosionLaw.rate(m, d), m + " " + t + " " + x);
            for (var mat : Metallurgy.values()) {
                var inside = CorrosionLaw.inside(mat, d);
                check(inside.rate(), mat + " " + t + " " + x);
                assertEquals(inside.rate() == 0, inside.mechanism() == null);
                check(CorrosionLaw.outside(mat, x), "outside " + x);
                check(CorrosionLaw.overheating(mat, t), "M8 " + t);
                check(CorrosionLaw.hydrogenAttack(mat, t, x), "M10 " + t + " " + x);
                check(CorrosionLaw.oxygenFire(mat, t, x), "M11 " + t + " " + x);
            }
        }
        assertSame(CorrosionLaw.Inside.NONE, CorrosionLaw.inside(CARBON_STEEL, CorrosionDigest.EMPTY));
    }

    private static void check(double r, String what) {
        assertTrue(r >= 0 && r <= CorrosionLaw.MAX_RATE, what + ": " + r);
    }
}

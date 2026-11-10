package com.wormzjl.icheme.science.fluid.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A flare stack's combustion and rating (batch 2026-11-09-flare-stack, FLARE_STACK_PLAN.md sections 1 and 5). */
class FlareTest {
    private static final double[][] FORMULAS = {{1, 4, 0, 0, 0}, {0, 0, 2, 0, 0}, {0, 2, 0, 0, 0}, {1, 0, 0, 2, 0}, {0, 2, 0, 1, 0}};
    /** Methane, nitrogen, hydrogen, carbon dioxide, water: their lower heating values, J/mol. */
    private static final double[] LHV = {802618, 0, 241814, 0, 0};

    private static double flame(double... moles) {
        return Flare.flameTemperature(moles, LHV, FORMULAS);
    }

    /** The owner's 1700 K is methane at its inerting limits; the regulatory 7.45 MJ/scm floor sits near 1900 K. */
    @Test void methanesInertingLimitsGiveTheFlameLimit() {
        assertEquals(1700, flame(1, 6, 0, 0, 0), 5, "6 N2 : 1 CH4 (Bulletin 627)");
        assertEquals(1765, flame(1, 0, 0, 3.2, 0), 15, "3.2 CO2 : 1 CH4: CO2 inerts at a little less");
        double floor = 7.45e6 * Flare.STANDARD_VOLUME / LHV[0];
        assertEquals(.223, floor, .001, "7.45 MJ/scm is 22 % methane");
        assertEquals(1925, flame(floor, 1 - floor, 0, 0, 0), 10);
        assertTrue(Flare.burns(flame(1, 5, 0, 0, 0)) && !Flare.burns(flame(1, 7, 0, 0, 0)));
        assertTrue(flame(1, 0, 0, 0, 0) > 2200, "pure methane burns hot: " + flame(1, 0, 0, 0, 0));
        assertEquals(2666, flame(0, 0, 1, 0, 0), 5, "hydrogen alone");
        assertEquals(Flare.AMBIENT, flame(0, 1, 0, 0, 0), "nitrogen gives nothing");
        assertEquals(Flare.AMBIENT, flame(0, 0, 0, 0, 0), "no gas");
        assertTrue(flame(1, 3, 0, 0, 0) > flame(1, 3, 0, 0, 1), "steam dilutes too");
        assertEquals(flame(2, 12, 0, 0, 0), flame(1, 6, 0, 0, 0), 1e-9, "by composition, not amount");
    }

    @Test void theStableVelocityFollowsTheHeatingValue() {
        double methane = Flare.netHeatingValue(new double[]{1, 0, 0, 0, 0}, LHV);
        assertEquals(33.39e6, methane, 1e4, "methane 33.4 MJ/scm");
        assertEquals(91.6, Flare.stableVelocity(methane), .2, "40 CFR 60.18 for methane: about Mach 0.2");
        assertEquals(18.3, Flare.stableVelocity(0), "a lean gas: the low velocity");
        assertEquals(122, Flare.stableVelocity(60e6), "never above 122 m/s");
        assertEquals(methane / 2, Flare.netHeatingValue(new double[]{1, 1, 0, 0, 0}, LHV), 1e-6);
    }

    @Test void oneSizeSetsTheRatingAndTheStack() {
        assertEquals(212e6, Flare.rated(.3), 2e6, "a 0.3 m tip: about 4.2 kg/s of methane");
        assertEquals(32, Flare.stackHeight(.3), "its flame 46 m from the base, 30 m long");
        assertEquals(10, Flare.stackHeight(Flare.MIN_TIP));
        assertEquals(65, Flare.stackHeight(Flare.MAX_TIP));
        for (double tip = Flare.MIN_TIP; tip < Flare.MAX_TIP; tip += Flare.TIP_STEP)
            assertTrue(Flare.stackHeight(tip + Flare.TIP_STEP) >= Flare.stackHeight(tip) && Flare.rated(tip + Flare.TIP_STEP) > Flare.rated(tip), "grows with the tip at " + tip);
        assertEquals(29.6, Flare.flameLength(212.4e6), .2);
    }

    @Test void theLimitIsTheSmallerOfHeatAndVelocity() {
        double methane = Flare.METHANE_DENSITY * Flare.stableVelocity(Flare.METHANE_NHV) * Flare.tipArea(.3);
        assertEquals(methane, Flare.limit(.3, Flare.METHANE_PER_KG, Flare.METHANE_NHV, Flare.METHANE_DENSITY), 1e-9, "methane meets both at once");
        assertEquals(.082 * 18.3 * Flare.tipArea(.3), Flare.limit(.3, 1.2e8, 10.06e6, .082), 1e-9, "hydrogen: light and lean per m3, its velocity binds");
        assertEquals(Flare.rated(.3) / 5e7, Flare.limit(.3, 5e7, 60e6, 2), 1e-9, "a dense rich gas: its heat binds");
        assertEquals(1.145 * 18.3 * Flare.tipArea(.3), Flare.limit(.3, 0, 0, 1.145), 1e-9, "an inert: the low velocity");
    }

    /** Methane, ethane, propane, ethylene, carbon monoxide, hydrogen: atoms of C, H, N, O, S. */
    private static final double[][] FUELS = {{1, 4, 0, 0, 0}, {2, 6, 0, 0, 0}, {3, 8, 0, 0, 0}, {2, 4, 0, 0, 0}, {1, 0, 0, 1, 0}, {0, 2, 0, 0, 0}};

    private static double soot(int fuel, double load) {
        var moles = new double[FUELS.length];
        moles[fuel] = 1;
        return Flare.soot(moles, FUELS, load);
    }

    /** The soot index (batch 2026-11-10-custom-shader-system, W3, owner X7 A): methane none, propane some at full load, none unloaded. */
    @Test void sootFollowsTheCarbonToHydrogenRatioAndTheLoad() {
        assertEquals(2.98, Flare.carbonToHydrogen(new double[]{1, 0, 0, 0, 0, 0}, FUELS), .01, "methane");
        assertEquals(0, soot(0, 1), "methane burns clean at any load");
        assertEquals(.28, soot(1, 1), .01, "ethane: slight");
        assertEquals(.45, soot(2, 1), .01, "propane smokes at full load");
        assertEquals(.98, soot(3, 1), .01, "ethylene: nearly all of it");
        assertEquals(0, soot(2, 0), "no load, no smoke");
        assertEquals(soot(2, 1), soot(2, Flare.SOOT_LOAD), 1e-12, "the full index from 80 % of the rating");
        assertEquals(soot(2, 1) / 2, soot(2, Flare.SOOT_LOAD / 2), 1e-12, "half of it at 40 %");
        assertEquals(0, soot(4, 1), "carbon bound to oxygen does not soot");
        assertEquals(0, soot(5, 1), "hydrogen has no carbon");
        assertEquals(0, Flare.soot(new double[FUELS.length], FUELS, 1), "no gas");
    }

    @Test void theExitVelocityIsTheVolumeFlowOverTheTip() {
        assertEquals(10 / (.65 * Flare.tipArea(.3)), Flare.exitVelocity(10, .65, .3), 1e-9);
        assertEquals(0, Flare.exitVelocity(-1, .65, .3), "no reverse flow");
        assertEquals(0, Flare.exitVelocity(1, 0, .3), "no density, no velocity");
    }
}

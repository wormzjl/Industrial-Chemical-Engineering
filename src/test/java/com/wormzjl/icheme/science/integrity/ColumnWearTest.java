package com.wormzjl.icheme.science.integrity;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A column's trays and wall (batch 2026-11-16-fouling-corrosion, plan section 13.1, WP8): the numbers of decision D10.
 */
class ColumnWearTest {
    @Test
    void oneWeightPercentFoulsTheTraysHalfAndLeaves80PercentOfTheirCapacity() {
        double share = ColumnWear.share(1, 99);
        assertEquals(.01, share, 1e-15);
        assertEquals(.5, ColumnWear.asymptote(share), 1e-12);
        assertEquals(.8, ColumnWear.capacity(ColumnWear.asymptote(share)), 1e-12);
        assertTrue(ColumnWear.fouled(ColumnWear.asymptote(share)), "TRAYS FOULED from 0.5");
        // From 2 wt% the trays foul whole: 60 % of their clean capacity.
        assertEquals(1, ColumnWear.asymptote(.02), 0);
        assertEquals(1, ColumnWear.asymptote(.05), 0);
        assertEquals(.6, ColumnWear.capacity(1), 1e-12);
        assertEquals(1, ColumnWear.capacity(0), 0, "clean trays: exactly 1");
    }

    @Test
    void theFoulingApproachesItsAsymptoteWithTheFoulingTimeAndNeverFallsByItself() {
        double tau = 10 * 3600;
        assertEquals(.5 * (1 - Math.exp(-1)), ColumnWear.foul(0, .01, tau, tau), 1e-12, "one time constant: 63 % of the way");
        assertEquals(.5, ColumnWear.foul(0, .01, 100 * tau, tau), 1e-12);
        assertEquals(.7, ColumnWear.foul(.7, .01, tau, tau), 0, "above its asymptote it stays");
        assertEquals(.7, ColumnWear.foul(.7, 0, tau, tau), 0, "no solids: it stays");
        assertEquals(0, ColumnWear.foul(0, 0, tau, tau), 0);
        assertEquals(0, ColumnWear.share(0, 0), 0, "a cycle that took nothing");
    }

    @Test
    void theSolidsWearOneWeightPercentHalfAMillimetreAYearOnCarbonSteelAndAQuarterOnStainless() {
        assertEquals(.5, ColumnWear.solids(Metallurgy.CARBON_STEEL, .01), 1e-12);
        assertEquals(.25, ColumnWear.solids(Metallurgy.STAINLESS_STEEL, .01), 1e-12);
        assertEquals(.4, ColumnWear.solids(Metallurgy.CHROME_STEEL, .01), 1e-12);
        assertEquals(.2, ColumnWear.solids(Metallurgy.NICKEL_ALLOY, .01), 1e-12);
        assertEquals(5, ColumnWear.solids(Metallurgy.CARBON_STEEL, .1), 0, "capped at 5 mm/y");
        assertEquals(5, ColumnWear.solids(Metallurgy.CARBON_STEEL, .5), 0);
        assertEquals(0, ColumnWear.solids(Metallurgy.CARBON_STEEL, 0), 0);
    }

    @Test
    void theWallIsBookedPerCorrosionYearUpToItsCap() {
        // 1 mm/y over one corrosion year (20 process hours): 1 mm.
        assertEquals(1, ColumnWear.lose(0, 1, 20 * 3600, 20, 1e3), 1e-12);
        assertEquals(2.5, ColumnWear.lose(2, .5, 20 * 3600, 20, 1e3), 1e-12);
        assertEquals(1e3, ColumnWear.lose(999.9, 5, 20 * 3600, 20, 1e3), 0, "capped");
        assertEquals(2, ColumnWear.lose(2, 0, 20 * 3600, 20, 1e3), 0, "no rate, no loss");
    }
}

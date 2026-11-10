package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The batch still's head in the running world (batch 2026-11-09-batch-distiller, W3; BATCH_STILL_ENGINE.md): the condenser's
 * split of what reached the head, the distillate's share at a reflux ratio and a condenser share, and the column's answer on
 * a pentane pot as the pot's {@link Pool.Head}.
 */
class StillHeadTest {
    private static final FluidThermodynamics PENTANES = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), "icheme:refinery").on(List.of("Isopentane", "N-pentane"));

    /** A full condenser sends the net top product out as liquid; a closed valve none unless it condenses all; no condenser none. */
    @Test
    void theDistillatesShareFollowsTheRefluxAndTheCondenser() {
        assertEquals(1, StillHead.liquidShare(2, 1), 1e-12, "a full condenser: all of the net top product is distillate");
        assertEquals(1, StillHead.liquidShare(Double.POSITIVE_INFINITY, 1), 1e-12, "a closed valve with a full condenser vents nothing");
        assertEquals(0, StillHead.liquidShare(Double.POSITIVE_INFINITY, .9), 1e-12, "a closed valve short of water vents its top product");
        assertEquals(0, StillHead.liquidShare(2, 0), 1e-12, "no water: no condensate");
        // R = 2, φ = 0.5: R_eff = 0.5 · 2 / (3 - 1) = 0.5, so the share is 0.5 · 1.5 / 3.
        assertEquals(.25, StillHead.liquidShare(2, .5), 1e-12);
        double last = 0;
        for (double phi = 0; phi <= 1; phi += .05) {
            double share = StillHead.liquidShare(2, phi);
            assertTrue(share >= last - 1e-12 && share <= 1, "the share grows with the condenser's: " + phi);
            last = share;
        }
    }

    /** A gas that cannot condense vents whole; the rest splits at the liquid share; the energy goes by the moles. */
    @Test
    void theCondenserVentsWhatCannotCondenseAndSplitsTheRest() {
        double[] arrived = {1, 2, 3}, critical = {190.6, 469.7, StillHead.WATER_CRITICAL};
        var d = StillHead.divide(arrived, 600, critical, 300, .5, true);
        assertArrayEquals(new double[]{0, 1, 1.5}, d.distillate(), 1e-12, "methane vents; pentane and water half condense");
        assertArrayEquals(new double[]{1, 1, 1.5}, d.vent(), 1e-12);
        assertEquals(600 * 2.5 / 6, d.distillateEnergy(), 1e-9, "the energy by the moles' share");
        assertEquals(600, d.distillateEnergy() + d.ventEnergy(), 1e-9, "no energy lost in the split");
        var open = StillHead.divide(arrived, 600, critical, 300, 1, false);
        assertArrayEquals(new double[3], open.distillate(), 0, "with no condenser everything vents");
        assertEquals(600, open.ventEnergy(), 1e-9);
        var none = StillHead.divide(new double[3], 50, critical, 300, 1, true);
        assertEquals(0, none.distillateEnergy() + none.ventEnergy(), 0, "energy with no moles stays the condenser's");
    }

    /** The column returns the heavier pentane: the head passes more isopentane than n-pentane, and all the water. */
    @Test
    void theColumnsAnswerIsThePotsHead() {
        int n = PENTANES.componentCount();
        assertEquals(3, n, "two pentanes and water");
        double[] pot = new double[n];
        pot[0] = 5;
        pot[1] = 5;
        var outcome = StillHead.run(new StillHead.Job(pot, 305, BatchStill.ATMOSPHERIC, 4, 3, null, .15), PENTANES, () -> {
        });
        var solved = assertInstanceOf(StillHead.Solved.class, outcome, () -> "the column solves: " + outcome);
        var recovery = solved.head().recovery();
        assertEquals(n, recovery.length);
        for (double r : recovery) assertTrue(r >= 0 && r <= 1, "a share: " + r);
        assertTrue(recovery[0] > recovery[1], "isopentane leaves the head before n-pentane: " + recovery[0] + " / " + recovery[1]);
        assertEquals(1, recovery[n - 1], 0, "water is not in the column's basis: it leaves with the distillate");
        assertTrue(solved.headTemperature() > 290 && solved.headTemperature() < 315, "the head near the pentanes' boiling points: " + solved.headTemperature());
        assertTrue(solved.condensateTemperature() <= solved.headTemperature() + 1e-9, "the condensate at or below the head vapour");
        assertTrue(solved.fPerWatt() > 0, "a column has an F-factor per watt of boil-up");
        assertTrue(Double.isFinite(solved.head().returnEnthalpy()), "the liquid back to the pot carries its enthalpy");
        assertEquals(StillHead.WATER_CRITICAL, solved.critical()[n - 1], 0);
        // Warm from its profile, the next solve gives the same answer.
        var warm = assertInstanceOf(StillHead.Solved.class, StillHead.run(new StillHead.Job(pot, 305, BatchStill.ATMOSPHERIC, 4, 3, solved.profile(), .15), PENTANES, () -> {
        }));
        assertArrayEquals(recovery, warm.head().recovery(), 1e-6, "a warm start lands on the same split");
    }
}

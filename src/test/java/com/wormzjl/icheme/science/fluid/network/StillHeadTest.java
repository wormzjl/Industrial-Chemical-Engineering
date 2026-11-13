package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
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

    /**
     * Each component splits at its liquid share at the outlet times the condenser's share: a gas with none at the outlet vents
     * whole; before the column's first answer nothing condenses; the energy goes by the moles.
     */
    @Test
    void theCondenserSplitsEachComponentAtItsLiquidShare() {
        double[] arrived = {1, 2, 3}, shares = {0, 1, 1};
        var d = StillHead.divide(arrived, 600, shares, .5, true);
        assertArrayEquals(new double[]{0, 1, 1.5}, d.distillate(), 1e-12, "methane vents; pentane and water half condense");
        assertArrayEquals(new double[]{1, 1, 1.5}, d.vent(), 1e-12);
        assertEquals(600 * 2.5 / 6, d.distillateEnergy(), 1e-9, "the energy by the moles' share");
        assertEquals(600, d.distillateEnergy() + d.ventEnergy(), 1e-9, "no energy lost in the split");
        var open = StillHead.divide(arrived, 600, shares, 1, false);
        assertArrayEquals(new double[3], open.distillate(), 0, "with no condenser everything vents");
        assertEquals(600, open.ventEnergy(), 1e-9);
        var none = StillHead.divide(new double[3], 50, shares, 1, true);
        assertEquals(0, none.distillateEnergy() + none.ventEnergy(), 0, "energy with no moles stays the condenser's");
        var unanswered = StillHead.divide(arrived, 600, null, 1, true);
        assertArrayEquals(new double[3], unanswered.distillate(), 0, "before the column's first answer nothing is known to condense");
        var partial = StillHead.divide(arrived, 600, new double[]{.25, .5, 1}, 1, true);
        assertArrayEquals(new double[]{.25, 1, 3}, partial.distillate(), 1e-12, "a partial condenser's shares");
    }

    /** The column returns the heavier pentane: the head passes more isopentane than n-pentane; a pot without water sends no flash to the engine. */
    @Test
    void theColumnsAnswerIsThePotsHead() {
        int n = PENTANES.componentCount();
        assertEquals(3, n, "two pentanes and water");
        double[] pot = new double[n];
        pot[0] = 5;
        pot[1] = 5;
        var outcome = StillHead.run(new StillHead.Job(pot, 305, BatchStill.ATMOSPHERIC, 4, 3, null, .15, StillColumn.TOTAL_CONDENSER), PENTANES, () -> {
        });
        var solved = assertInstanceOf(StillHead.Solved.class, outcome, () -> "the column solves: " + outcome);
        var recovery = solved.head().recovery();
        assertEquals(n, recovery.length);
        for (double r : recovery) assertTrue(r >= 0 && r <= 1, "a share: " + r);
        assertTrue(recovery[0] > recovery[1], "isopentane leaves the head before n-pentane: " + recovery[0] + " / " + recovery[1]);
        assertEquals(1, recovery[n - 1], 0, "no water in the pot: its share stands at 1");
        assertEquals(0, solved.flashes(), "no engine split on a pot without water (W4 D7)");
        assertFalse(solved.waterOnTrays());
        assertTrue(solved.headTemperature() > 290 && solved.headTemperature() < 315, "the head near the pentanes' boiling points: " + solved.headTemperature());
        assertTrue(solved.condensateTemperature() <= solved.headTemperature() + 1e-9, "the condensate at or below the head vapour");
        assertTrue(solved.fPerWatt() > 0, "a column has an F-factor per watt of boil-up");
        assertTrue(Double.isFinite(solved.head().returnEnthalpy()), "the liquid back to the pot carries its enthalpy");
        for (double share : solved.liquidShares()) assertEquals(1, share, 0, "a pentane head condenses whole above the outlet");
        // Warm from its profile, the next solve gives the same answer.
        var warm = assertInstanceOf(StillHead.Solved.class, StillHead.run(new StillHead.Job(pot, 305, BatchStill.ATMOSPHERIC, 4, 3, solved.profile(), .15, StillColumn.TOTAL_CONDENSER), PENTANES, () -> {
        }));
        assertArrayEquals(recovery, warm.head().recovery(), 1e-6, "a warm start lands on the same split");
    }

    /**
     * A wash of 10 % ethanol by volume (W4 D1): the column carries its water, so the head returns most of the water with
     * the reflux and passes ethanol before it; the shares are the column's, water's among them.
     */
    @Test
    void aWashsHeadReturnsItsWater() {
        assertWashHead();
    }

    /**
     * Water under n-hexane at reflux 5 draws its reflux on the water layer: two liquids on the trays, so the column's answer is
     * the pot's single stage with its marker (W4 plan section 12), which the head carries.
     */
    @Test
    void twoLiquidsOnTheTraysGiveThePotsSingleStage() {
        var model = FluidThermodynamics.forNetwork(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID).on(List.of("N-hexane"));
        int n = model.componentCount();
        double[] pot = new double[n];
        pot[model.components().indexOf("N-hexane")] = 5;
        pot[n - 1] = 5;
        var outcome = StillHead.run(new StillHead.Job(pot, 334, BatchStill.ATMOSPHERIC, 4, 5, null, .15, StillColumn.TOTAL_CONDENSER), model, () -> {
        });
        var solved = assertInstanceOf(StillHead.Solved.class, outcome, () -> "the column answers: " + outcome);
        assertTrue(solved.potStill(), "the marker");
        assertEquals(StillColumn.POT_STILL, solved.notice());
    }

    /**
     * Code review finding 2: a column whose trays do not solve runs as the pot's single stage. The medium crude in the
     * tutorial's 500 L still with 4 trays at reflux 1 (chapter 3, tools/tutorial-recordings/StillTrays), its pot at 115 °C
     * after the light gas has gone: the tray solve fails cold and warm, and the head answers with the pot's single stage, the
     * trays' failure its notice. At a 30 °C condenser outlet the gas stays gas and the naphtha condenses.
     */
    @Test
    void traysThatDoNotSolveGiveThePotsSingleStage() {
        var model = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog.NETWORK_PACKAGE);
        double[] pot = {
            0.0, 0.0022625252366497636, 2.0311600915328696, 3.7853015233320737, 11.058328559701161, 13.52425750804062,
            18.80952816360237, 69.33998829124849, 140.71720552379952, 150.61734657959096, 143.48196014957185, 125.89443085074299,
            104.0344174660747, 91.7574535474477, 83.0424757665696, 64.46596428282525, 34.1361776421039, 16.614663083325915,
            9.330721335352653, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0};
        assertEquals(model.hydrocarbon.componentCount() + 1, pot.length, "the network's basis, water last");
        double outlet = 303.15, diameter = BatchStill.columnDiameter(new BatchStill.Design(.5, BatchStill.Heater.SOLID, true, 4).rating());
        assertFalse(new StillColumn(model, BatchStill.ATMOSPHERIC).solve(pot, 4, 1, outlet, null).converged(), "the trays do not solve here");
        var outcome = StillHead.run(new StillHead.Job(pot, 388.212694, BatchStill.ATMOSPHERIC, 4, 1, null, diameter, outlet), model, () -> {
        });
        var solved = assertInstanceOf(StillHead.Solved.class, outcome, () -> "the single stage answers: " + outcome);
        assertTrue(solved.potStill(), "the marker");
        assertTrue(solved.notice().startsWith("no convergence"), solved.notice());
        assertEquals(outlet, solved.condensateTemperature(), 1e-9, "the condenser's outlet");
        var names = model.components();
        var shares = solved.liquidShares();
        for (var gas : List.of("Methane", "Ethane", "Propane")) assertTrue(shares[names.indexOf(gas)] < .1, gas + " stays gas: " + shares[names.indexOf(gas)]);
        for (var naphtha : List.of("crude_pc02", "crude_pc03", "crude_pc04", "crude_pc05")) assertTrue(shares[names.indexOf(naphtha)] > .9, naphtha + " condenses: " + shares[names.indexOf(naphtha)]);
    }

    private static void assertWashHead() {
        var model = FluidThermodynamics.forNetwork(PilotAqueousTestCatalog.catalog(), PilotAqueousTestCatalog.PACKAGE_ID).on(List.of("Ethanol"));
        int n = model.componentCount(), e = model.components().indexOf("Ethanol");
        double[] pot = new double[n];
        pot[e] = .332;
        pot[n - 1] = 9.668;
        var outcome = StillHead.run(new StillHead.Job(pot, 365, BatchStill.ATMOSPHERIC, 4, 3, null, .15, StillColumn.TOTAL_CONDENSER), model, () -> {
        });
        var solved = assertInstanceOf(StillHead.Solved.class, outcome, () -> "the column solves: " + outcome);
        var recovery = solved.head().recovery();
        System.out.printf(java.util.Locale.ROOT, "W4 wash head: ethanol share %.4f, water share %.4f, head %.2f C, condensate %.2f C, %d flashes%n",
                recovery[e], recovery[n - 1], solved.headTemperature() - 273.15, solved.condensateTemperature() - 273.15, solved.flashes());
        assertTrue(recovery[n - 1] < .5, "the column returns most of the water: " + recovery[n - 1]);
        assertTrue(recovery[e] > 2 * recovery[n - 1], "ethanol leaves the head before water");
        assertTrue(solved.flashes() > 0, "the wash's liquids hold water: the engine states them");
        for (double share : solved.liquidShares()) assertEquals(1, share, 0, "a wash's head condenses whole above the outlet");
        assertTrue(Double.isFinite(solved.head().returnEnthalpy()));
    }
}

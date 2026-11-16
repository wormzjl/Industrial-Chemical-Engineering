package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.DenseLabel;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a player reads on one dense fluid (S5 of batch 2026-11-15-supercritical-fluids, owner 2026-11-16, decision 1):
 * carbon dioxide at 310 K reads "Dense fluid" above reduced density 0.5 in either slot, and as gas at or below it; nitrogen
 * at 300 K and 1 bar, dense by its isotherm but at reduced density 0.004, reads gas as before; a node of two phases reads
 * as before. The simulator's stream has no vapour share where it reads "Dense fluid", and its phase share is named "Dense"
 * in the slot that holds it, so no word and no reading follows the slot rule's line at reduced density 1.
 */
class DenseWordsTest {
    private static final FluidThermodynamics MODEL = SheetFixture.FLUIDS.model();

    private static double[] pure(String name) {
        double[] n = new double[MODEL.componentCount()];
        n[MODEL.components().indexOf(name)] = 1;
        return n;
    }

    private static FluidThermodynamics.State at(double t, double p, double[] n) {
        return MODEL.flashTP(t, p, n, () -> {
        });
    }

    /** The pressure at which {@code n} at {@code t} stands at reduced density {@code target}. */
    private static double pressureAt(double t, double[] n, double target) {
        double lo = 1e5, hi = 8e7;
        for (int i = 0; i < 200 && hi - lo > 1e-13 * hi; i++) {
            double mid = (lo + hi) / 2;
            if (at(t, mid, n).reducedDensity() < target) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    private static StreamProps props(FluidThermodynamics.State s, double[] n) {
        return StreamProps.of(new FlowStream(s.temperature(), s.pressure(), n, s.enthalpy(), s.mass(), s), SheetFixture.FLUIDS);
    }

    private static Column.Split split(StreamProps p) {
        var group = Door.phases("Phases", p.phases(), new SheetFixture.Texts());
        return (Column.Split) group.rows().getFirst();
    }

    @Test void carbonDioxideAt310KReadsDenseAboveHalfItsCriticalDensityInEitherSlot() {
        double[] co2 = pure("CarbonDioxide");
        for (double delta : new double[]{.4, .5, .8, 1.0005, 1.6}) {
            var s = at(310, pressureAt(310, co2, delta), co2);
            assertTrue(s.dense(), "one dense fluid at " + delta);
            boolean expected = s.reducedDensity() > FluidThermodynamics.GAS_LIKE_UNTIL;
            assertEquals(expected, DenseLabel.dense(s), "the label at " + delta + " (" + s.reducedDensity() + ")");
            var p = props(s, co2);
            var shares = split(p).shares();
            int row = s.oneFluidSlot() == FluidThermodynamics.State.VAPOR ? 2 : 0;
            assertEquals(expected ? row : -1, p.phases().dense(), "the share that holds it at " + delta);
            assertEquals(expected ? "Dense" : row == 2 ? "Gas" : "Organic", shares.get(row).label(), "its share's name at " + delta);
            if (expected) assertTrue(Double.isNaN(p.vapour()), "no vapour share at " + delta);
            else assertEquals(100, p.vapour(), 1e-9, "all gas at " + delta);
        }
    }

    @Test void theSlotLineChangesNoWord() {
        double[] co2 = pure("CarbonDioxide");
        var below = at(310, pressureAt(310, co2, .9995), co2);
        var above = at(310, pressureAt(310, co2, 1.0005), co2);
        assertNotEquals(below.oneFluidSlot(), above.oneFluidSlot(), "the slot rule moves it here");
        var a = props(below, co2);
        var b = props(above, co2);
        assertTrue(a.phases().denseFluid() && b.phases().denseFluid());
        assertEquals(Double.isNaN(a.vapour()), Double.isNaN(b.vapour()));
        assertEquals(1, split(a).shares().stream().filter(x -> x.label().equals("Dense")).count());
        assertEquals(1, split(b).shares().stream().filter(x -> x.label().equals("Dense")).count());
    }

    @Test void nitrogenAt300KAnd1BarReadsGasAsBefore() {
        double[] n2 = pure("Nitrogen");
        var s = at(300, 1e5, n2);
        assertTrue(s.dense(), "on a supercritical isotherm");
        assertFalse(DenseLabel.dense(s), "at reduced density " + s.reducedDensity());
        var p = props(s, n2);
        assertFalse(p.phases().denseFluid());
        assertEquals(100, p.vapour(), 1e-9);
        assertEquals("Gas", split(p).shares().get(2).label());
    }

    @Test void aNodeOfTwoPhasesReadsAsBefore() {
        double[] n = new double[MODEL.componentCount()];
        n[MODEL.components().indexOf("Nitrogen")] = .2;
        n[n.length - 1] = .8;
        var s = at(298.15, 2e5, n);
        assertEquals(-1, s.oneFluidSlot(), "gas over water");
        assertFalse(DenseLabel.dense(s));
        assertEquals(-1, DenseLabel.row(s));
        var p = props(s, n);
        assertFalse(p.phases().denseFluid());
        assertTrue(p.vapour() > 0 && p.vapour() < 100, "a vapour share: " + p.vapour());
        var shares = split(p).shares();
        assertEquals("Gas", shares.get(2).label());
        assertEquals("Aqueous", shares.get(1).label());
    }
}

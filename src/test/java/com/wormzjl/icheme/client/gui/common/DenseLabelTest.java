package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The device screen's dense label (S5 of batch 2026-11-15-supercritical-fluids, owner 2026-11-16, decision 1; option A of
 * the main thread): the client evaluates the one fluid's root of a view, which carries amounts and no traits, on its own
 * catalog, and reads what the model's own state reads: carbon dioxide at 310 K is "Dense fluid" above reduced density 0.5
 * in either slot; nitrogen at 300 K and 1 bar and a node of two phases read as before. Where the client cannot say (a
 * compound its catalog lacks, no model) the label is today's. The model is built once per catalog. The column names the
 * slot that holds it "Dense", its level line "No interface", a pump's gas meter its head share.
 */
class DenseLabelTest {
    private static final FluidThermodynamics MODEL = FluidTestSupport.networkModel();

    private static double[] pure(String name) {
        double[] n = new double[MODEL.componentCount()];
        n[MODEL.components().indexOf(name)] = 1;
        return n;
    }

    private static FluidThermodynamics.State at(double t, double p, double[] n) {
        return MODEL.flashTP(t, p, n, () -> {
        });
    }

    private static double pressureAt(double t, double[] n, double target) {
        double lo = 1e5, hi = 8e7;
        for (int i = 0; i < 200 && hi - lo > 1e-13 * hi; i++) {
            double mid = (lo + hi) / 2;
            if (at(t, mid, n).reducedDensity() < target) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** The view's rows of {@code s}, as {@code FluidView.State.from} forms them: the oil, the aqueous liquid, the gas. */
    private static double[] volumes(FluidThermodynamics.State s) {
        return new double[]{s.labelVolume(FluidThermodynamics.OIL), s.labelVolume(FluidThermodynamics.AQUEOUS), s.vaporVolume()};
    }

    private static double[][] moles(FluidThermodynamics.State s) {
        return new double[][]{s.labelAmountsView(FluidThermodynamics.OIL).clone(), s.labelAmountsView(FluidThermodynamics.AQUEOUS).clone(), s.vaporAmounts()};
    }

    private static int client(FluidThermodynamics.State s) {
        return DenseLabel.row(MODEL, MODEL.components(), s.temperature(), s.pressure(), volumes(s), moles(s));
    }

    @Test void theClientReadsWhatTheModelsStateReads() {
        double[] co2 = pure("CarbonDioxide");
        for (double delta : new double[]{.4, .5, .8, 1.0005, 1.6}) {
            var s = at(310, pressureAt(310, co2, delta), co2);
            int expected = s.reducedDensity() > FluidThermodynamics.GAS_LIKE_UNTIL ? (s.oneFluidSlot() == FluidThermodynamics.State.VAPOR ? 2 : 0) : -1;
            assertEquals(expected, DenseLabel.row(s), "the state's label at " + delta + " (" + s.reducedDensity() + ")");
            assertEquals(expected, client(s), "the client's label at " + delta);
        }
        var nitrogen = at(300, 1e5, pure("Nitrogen"));
        assertTrue(nitrogen.dense());
        assertEquals(-1, client(nitrogen), "nitrogen at 1 bar reads Vapor");
        double[] wet = new double[MODEL.componentCount()];
        wet[MODEL.components().indexOf("Nitrogen")] = .2;
        wet[wet.length - 1] = .8;
        assertEquals(-1, client(at(298.15, 2e5, wet)), "two phases read as before");
        assertEquals(-1, client(at(250, 3e6, co2)), "a subcritical liquid reads as before");
    }

    /** Owner condition 2 (main thread, 2026-11-16): a view the client cannot evaluate keeps today's label, never a failure. */
    @Test void aViewTheClientCannotEvaluateKeepsTodaysLabel() {
        double[] co2 = pure("CarbonDioxide");
        var s = at(310, pressureAt(310, co2, 1.2), co2);
        assertEquals(0, client(s));
        var axis = new ArrayList<>(MODEL.components());
        axis.set(axis.indexOf("CarbonDioxide"), "example:unobtainium");
        assertEquals(-1, DenseLabel.row(MODEL, axis, s.temperature(), s.pressure(), volumes(s), moles(s)), "a compound the client lacks");
        assertEquals(-1, DenseLabel.row(null, MODEL.components(), s.temperature(), s.pressure(), volumes(s), moles(s)), "no model");
        assertEquals(-1, DenseLabel.row(MODEL, MODEL.components(), Double.NaN, s.pressure(), volumes(s), moles(s)), "no temperature");
        assertEquals(-1, DenseLabel.row(MODEL, List.of("CarbonDioxide"), s.temperature(), s.pressure(), volumes(s), moles(s)), "another axis");
    }

    @Test void theModelIsBuiltOncePerCatalog() {
        var catalog = MaterialCatalog.bundled();
        var first = DenseLabel.model(catalog);
        int built = DenseLabel.models();
        assertNotNull(first);
        assertSame(first, DenseLabel.model(catalog));
        assertEquals(built, DenseLabel.models(), "no second build for the same catalog");
    }

    @Test void theColumnNamesTheDenseSlotItsLevelAndThePumpsHead() {
        var texts = new HeatControlColumnTest.Recording();
        for (int row : new int[]{0, 2}) {
            var group = (Column.Group) DeviceColumn.phases(new DeviceColumn.Phases("volume", row == 0 ? new double[]{1, 0, 0, 0} : new double[]{0, 0, 1, 0}, false, "Phase fractions.", false, row), texts);
            var split = (Column.Split) group.rows().get(1);
            assertEquals("Dense", split.shares().get(row).label(), "the slot " + row + " that holds it");
            assertEquals(row == 0 ? "Vapor" : "Organic", split.shares().get(2 - row).label(), "the other slot keeps its name");
        }
        assertEquals("Dense fluid", texts.get("phase." + DeviceColumn.phaseKey(2, false, 2)));
        assertEquals("Dense", texts.get("phase_tab." + DeviceColumn.phaseKey(0, false, 0)));
        assertEquals("Vapor", texts.get("phase." + DeviceColumn.phaseKey(2, false, -1)));
        var head = DeviceColumn.suction(new DeviceColumn.Suction("—", "—", "99.6", "100.0", "—", true), texts);
        var reading = (Column.Reading) ((Column.Group) head.getFirst()).rows().get(2);
        assertEquals("Head share", reading.label());
        var gas = DeviceColumn.suction(new DeviceColumn.Suction("—", "—", "0.4", "100.0", "—"), texts);
        assertEquals("Gas", ((Column.Reading) ((Column.Group) gas.getFirst()).rows().get(2)).label());
        assertTrue(texts.missing.isEmpty(), "every line in English: " + texts.missing);
    }
}

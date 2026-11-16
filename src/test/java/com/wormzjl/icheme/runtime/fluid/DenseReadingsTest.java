package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.pollution.BurstPipes;
import com.wormzjl.icheme.science.fluid.network.PipeTransfer;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.pollution.HazardStandard;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One dense fluid in what the server reads off its own state (P2 of batch 2026-11-16-supercritical-followups, items 7, 8 and
 * 16): the level, the sight glass and a burst weigh it by its liquid-like weight {@code w} (0 at or below reduced density 0.5,
 * 1 from 1), so each is the gas's reading at 0.5 or less, continuous across the slot rule's 1, and the liquid slot's from 1.
 * Contents that are not one dense fluid read as before, to the bit.
 */
class DenseReadingsTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
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

    private static FluidThermodynamics.State co2(double delta) {
        double[] n = pure("CarbonDioxide");
        return at(310, pressureAt(310, n, delta), n);
    }

    /** Section 4's level: carbon dioxide at 310 K reads 0, 0.104, 0.648, 1 and 1 of full at reduced density 0.4, 0.6, 0.8, 1.0005 and 1.6. */
    @Test void aDenseFluidsLevelRisesWithItsDensity() {
        double[] deltas = {.4, .6, .8, 1.0005, 1.6}, full = {0, .104, .648, 1, 1};
        for (int i = 0; i < deltas.length; i++) {
            var s = co2(deltas[i]);
            assertTrue(s.dense(), "one dense fluid at " + deltas[i]);
            assertEquals(deltas[i] < 1 ? FluidThermodynamics.State.VAPOR : FluidThermodynamics.State.LIQUID, s.oneFluidSlot(), "the slot rule at " + deltas[i]);
            var view = FluidView.State.from(s, s.volume());
            double w = DenseReadings.weight(s);
            assertEquals(full[i], DenseReadings.levelVolume(view, w) / s.volume(), 1e-9, "the share of full at " + deltas[i]);
            var tank = new FluidView.Tank(2, false, 0, 0, s.volume(), s.pressure(), s.pressure());
            assertEquals(2 * full[i], tank.level(view, w), 1e-9, "the level's height at " + deltas[i]);
            // Before P2 the level read the slot: 0 in the vapour slot, full in the liquid slot.
            assertEquals(deltas[i] < 1 ? 0 : 2, tank.level(view), 1e-9, "the slot's level at " + deltas[i]);
        }
    }

    /** Contents that are not one dense fluid: the weight is NaN or 0, and the level is the old sum to the bit. */
    @Test void otherContentsReadTheirLevelAsBefore() {
        double[] wet = new double[MODEL.componentCount()];
        wet[MODEL.components().indexOf("Nitrogen")] = .2;
        wet[wet.length - 1] = .8;
        var states = List.of(at(298.15, 2e5, wet), at(250, 3e6, pure("CarbonDioxide")), at(300, 1e5, pure("Nitrogen")), at(350, 2e6, pure("Propane")));
        for (var s : states) {
            double w = DenseReadings.weight(s);
            assertFalse(w > 0, "no liquid-like weight: " + w);
            var view = FluidView.State.from(s, s.volume());
            var v = view.phaseVolumes();
            assertEquals(Double.doubleToRawLongBits(v[0] + v[1] + view.solids().volume()), Double.doubleToRawLongBits(DenseReadings.levelVolume(view, w)));
            var tank = new FluidView.Tank(2, false, 0, 0, s.volume(), s.pressure(), s.pressure());
            assertEquals(Double.doubleToRawLongBits(tank.level(view)), Double.doubleToRawLongBits(tank.level(view, w)));
        }
        assertTrue(Double.isNaN(DenseReadings.weight((FluidThermodynamics.State) null)));
    }

    private static HazardStandard standard() {
        var c = FluidWorldAuthority.hazardColumns(CATALOG, MODEL);
        return new HazardStandard(c[0], c[1], c[2], c[3], c[4], c[5]);
    }

    private static Releases.Spilled burst(FluidThermodynamics.State s) {
        int n = MODEL.componentCount();
        double[] gas = new double[n], liquid = new double[n], dense = new double[n];
        DenseReadings.release(s.totals(), s, gas, liquid, dense);
        return DenseReadings.heard(gas, liquid, standard().volatileMoles(dense), MODEL.molecularWeights());
    }

    /**
     * Section 4's release: dense carbon dioxide reaches the air whole and the burst reads gas, in either slot; a dense heavy
     * aromatic (o-xylene at 800 K, above its critical temperature of 630 K) stays mostly on the ground and reads liquid.
     */
    @Test void aDenseReleaseIsHeardByWhatReachesTheAir() {
        for (double delta : new double[]{.6, .8, 1.0005, 1.6}) {
            var s = co2(delta);
            double mass = s.mass();
            var spilled = burst(s);
            assertEquals(mass, spilled.gasKilograms() + spilled.liquidKilograms(), 1e-12 * mass, "every kilogram at " + delta);
            assertEquals(mass, spilled.gasKilograms(), 1e-9 * mass, "carbon dioxide flashes whole at " + delta);
            assertEquals(BurstPipes.Phase.GAS, BurstPipes.phase(spilled.gasKilograms(), spilled.liquidKilograms()));
            // What the air gets: the gas-like share whole, and the liquid-like share by its volatile share, 1 for carbon dioxide.
            int n = MODEL.componentCount();
            double[] gas = new double[n], liquid = new double[n], dense = new double[n];
            DenseReadings.release(s.totals(), s, gas, liquid, dense);
            double w = FluidThermodynamics.pumpLiquidLikeWeight(s);
            int c = MODEL.components().indexOf("CarbonDioxide");
            assertEquals(s.totals()[c] * (1 - w), gas[c], 1e-12 * s.totals()[c]);
            assertEquals(s.totals()[c] * w, liquid[c], 1e-12 * s.totals()[c]);
            assertEquals(1, standard().volatileShare(c), 0);
        }
        double[] xylene = pure("O-xylene");
        var heavy = at(800, pressureAt(800, xylene, 1.2), xylene);
        assertTrue(heavy.dense(), "o-xylene at 800 K is one dense fluid");
        var spilled = burst(heavy);
        assertTrue(spilled.liquidKilograms() > .95 * heavy.mass(), "mostly on the ground: " + spilled);
        assertEquals(BurstPipes.Phase.LIQUID, BurstPipes.phase(spilled.gasKilograms(), spilled.liquidKilograms()));
    }

    /** A state that is not one dense fluid splits by its phases and is heard by its slots' masses, as before, to the bit. */
    @Test void anOrdinaryReleaseIsAsBefore() {
        double[] wet = new double[MODEL.componentCount()];
        wet[MODEL.components().indexOf("Nitrogen")] = .2;
        wet[wet.length - 1] = .8;
        for (var s : List.of(at(298.15, 2e5, wet), at(250, 3e6, pure("CarbonDioxide")))) {
            int n = MODEL.componentCount();
            double[] moles = s.totals(), gas = new double[n], liquid = new double[n], dense = new double[n];
            DenseReadings.release(moles, s, gas, liquid, dense);
            double[] v = s.vaporAmountsView(), l = s.liquidAmountsView(), a = s.aqueousAmountsView();
            double gasMass = 0, liquidMass = 0;
            for (int c = 0; c < n; c++) {
                double all = v[c] + l[c] + a[c], held = Math.max(0, moles[c]), share = all > 0 ? Math.clamp(v[c] / all, 0, 1) : 1;
                assertEquals(Double.doubleToRawLongBits(held * share), Double.doubleToRawLongBits(gas[c]));
                assertEquals(Double.doubleToRawLongBits(held - held * share), Double.doubleToRawLongBits(liquid[c]));
                gasMass += gas[c] * MODEL.molecularWeights()[c];
                liquidMass += liquid[c] * MODEL.molecularWeights()[c];
            }
            assertTrue(Arrays.stream(dense).allMatch(d -> d == 0));
            var spilled = DenseReadings.heard(gas, liquid, standard().volatileMoles(dense), MODEL.molecularWeights());
            assertEquals(new Releases.Spilled(gasMass, liquidMass), spilled);
        }
    }

    /** A line's holdup: each side pools its rows and splits by the node it left where that is dense; else its rows as before. */
    @Test void aLinesHoldupSplitsByTheNodeItsStreamLeft() {
        int n = MODEL.componentCount(), c = MODEL.components().indexOf("CarbonDioxide");
        double[][] rows = new double[3][n];
        rows[2][c] = 2;
        double[] gas = new double[n], liquid = new double[n], dense = new double[n];
        DenseReadings.moved(rows, 3, .648, gas, liquid, dense);
        assertEquals(6 * (1 - .648), gas[c], 1e-12);
        assertEquals(6 * .648, liquid[c], 1e-12);
        assertEquals(6 * .648, dense[c], 1e-12);
        double[] g = new double[n], l = new double[n], d = new double[n];
        rows[0][c] = 1;
        DenseReadings.moved(rows, 3, Double.NaN, g, l, d);
        assertEquals(6, g[c], 0);
        assertEquals(3, l[c], 0);
        assertEquals(0, d[c], 0);
        // The moved stream's weight: each side's mass by the weight of the node it left; NaN where a side is not dense.
        double[] sums = {1 * .2 + 3 * .6, 4, 0};
        assertEquals(.5, DenseReadings.moved(sums), 1e-15);
        assertTrue(Double.isNaN(DenseReadings.moved(new double[]{0, 0, 0})), "nothing moved");
        assertTrue(Double.isNaN(DenseReadings.moved(new double[]{1, 2, 1})), "a side left a node that is not dense");
    }

    /**
     * Section 4's sight glass: a stream whose node is one dense fluid shows its liquid colour with its opacity times {@code w},
     * no bubbles; clear at {@code w} 0, the liquid slot's glass at 1; any other stream as before.
     */
    @Test void aDenseStreamsGlassTintsWithItsWeight() {
        var catalog = MaterialCatalog.bundled();
        String pack = com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(catalog);
        var components = MODEL.components();
        var weights = MODEL.molecularWeights();
        int c = components.indexOf("CarbonDioxide");
        var mappings = List.of(new PhysicalFluidTopology.View(0, 7, true, .5, PhysicalFluidTopology.Direction.EAST));
        // The same stream in the vapour row (reduced density below 1) and in the oil row (from 1).
        double[][] gasRow = new double[3][components.size()], oilRow = new double[3][components.size()];
        gasRow[2][c] = 10;
        oilRow[0][c] = 10;
        var inGas = glass(new PipeTransfer.Stream(.44, gasRow, new double[]{0, 0, 1e-3}), components, weights, catalog, pack, mappings, .648);
        var inOil = glass(new PipeTransfer.Stream(.44, oilRow, new double[]{1e-3, 0, 0}), components, weights, catalog, pack, mappings, 1);
        var before = glass(new PipeTransfer.Stream(.44, oilRow, new double[]{1e-3, 0, 0}), components, weights, catalog, pack, mappings, Double.NaN);
        assertEquals(before, inOil, "from reduced density 1: the liquid slot's glass");
        assertEquals(0, inGas.bubbles(), "one dense fluid makes no bubbles");
        assertEquals(inOil.argb() & 0xFFFFFF, inGas.argb() & 0xFFFFFF, "one colour on both sides");
        assertEquals(Math.round((inOil.argb() >>> 24) * .648), inGas.argb() >>> 24, "its opacity times w");
        assertEquals(PipePresentation.SightGlass.CLEAR, glass(new PipeTransfer.Stream(.44, gasRow, new double[]{0, 0, 1e-3}), components, weights, catalog, pack, mappings, 0), "a gas-like fluid: clear");
        assertEquals(PipePresentation.SightGlass.CLEAR, glass(new PipeTransfer.Stream(.44, gasRow, new double[]{0, 0, 1e-3}), components, weights, catalog, pack, mappings, Double.NaN), "a gas, as before");
    }

    private static PipePresentation.SightGlass glass(PipeTransfer.Stream stream, List<String> components, double[] weights, MaterialCatalog catalog, String pack,
                                                     List<PhysicalFluidTopology.View> mappings, double w) {
        var info = new FluidView.PipeInfo(stream, List.of(new FluidView.PipeConnection(7, 1, 1, 0, false)), false, false);
        var view = new FluidView(1, 0, 0, 0, "FULL", null, 0, List.of(), null, 1, List.of(), null).withPipeInfo(info, 1);
        return PipePresentation.sightGlass(view, components, weights, catalog, pack, mappings, w);
    }
}

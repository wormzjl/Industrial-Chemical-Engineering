package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.unit.EjectorUnit;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Main plan W10's gate (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12): the law reproduces the section's
 * table to its printed digits; the tile closes mass, energy and components on a free and a wired Motive port; a wired motive under
 * the need is red, between it and 1.20 times it yellow; Auto's suction holds the load with its spare.
 */
final class EjectorTest {
    private static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, PACKAGE, () -> FluidThermodynamics.forNetwork(CATALOG, PACKAGE));
    private static final FlowThermo THERMO = new FlowThermo(MODEL, CATALOG, PACKAGE, () -> {
    });
    private static final List<String> AXIS = MODEL.components();
    private static final int WATER = AXIS.size() - 1;
    /** Section 3.12's table: motive 1000 kPa, discharge 120 kPa, the load at 50 °C. */
    private static final double MOTIVE = 1e6, DISCHARGE = 1.2e5, LOAD = 323.15;
    /** The load: nitrogen with a little steam, at 20 kPa, mol/s. */
    private static final double SUCTION = 2e4, NITROGEN_FLOW = 10, STEAM_FLOW = .5;

    private static <T> T on(Supplier<T> test) {
        return MaterialRuntime.with(CATALOG, PACKAGE, test::get);
    }

    @Test void theLawReproducesSection312sTable() {
        double[] suctions = {50, 30, 20, 12};
        double[][] table = {{1.64, 1.29, .69}, {2.80, 2.21, 1.19}, {4.30, 3.39, 1.82}, {7.37, 5.81, 3.13}};
        double[] masses = {EjectorLaw.WATER, 29, 100};
        for (int row = 0; row < suctions.length; row++)
            for (int column = 0; column < masses.length; column++) {
                double ratio = EjectorLaw.ratio(MOTIVE, suctions[row] * 1e3, DISCHARGE, LOAD, masses[column]);
                assertEquals(table[row][column], Math.round(ratio * 100) / 100., 1e-9, suctions[row] + " kPa, M " + masses[column]);
            }
        // The column block's example: 5000 kg/h of M 100 at 12 kPa, to 120 kPa on 10 bar steam.
        assertEquals(15640, Math.round(5000 * EjectorLaw.ratio(MOTIVE, 12e3, DISCHARGE, LOAD, 100) / 10) * 10., 1e-9);
        // The vendors' load factors against air.
        assertEquals(.26, Math.round(100 * EjectorLaw.ratio(MOTIVE, 12e3, DISCHARGE, LOAD, 29) / EjectorLaw.ratio(MOTIVE, 12e3, DISCHARGE, LOAD, 2)) / 100., 1e-9);
    }

    @Test void pcfStaysAtItsLeastAboveTheEdgeWithZeroSlope() {
        // Above 1500 kPa only (P_m / P_s)^0.015 moves the steam.
        for (double motive : new double[]{1.6e6, 2e6, 3.5e6, 5e6})
            assertEquals(Math.pow(motive / 1.5e6, .015), EjectorLaw.ratio(motive, 12e3, DISCHARGE, LOAD, 100) / EjectorLaw.ratio(1.5e6, 12e3, DISCHARGE, LOAD, 100), 1e-12, motive + " Pa");
        double below = EjectorLaw.ratio(1.5e6 - 1, 12e3, DISCHARGE, LOAD, 100), at = EjectorLaw.ratio(1.5e6, 12e3, DISCHARGE, LOAD, 100), above = EjectorLaw.ratio(1.5e6 + 1, 12e3, DISCHARGE, LOAD, 100);
        assertEquals(above - at, at - below, 1e-9 * at, "the same slope on both sides of the edge");
    }

    @Test void theFittedRangeIsTheCorrelations() {
        assertTrue(EjectorLaw.fitted(MOTIVE, 30e3, DISCHARGE, LOAD), "2.80 kg/kg at a compression of 4");
        assertFalse(EjectorLaw.fitted(MOTIVE, 12e3, DISCHARGE, LOAD), "7.37 kg/kg is past 4");
        assertFalse(EjectorLaw.fitted(MOTIVE, 70e3, DISCHARGE, LOAD), "a compression of 1.71 is under 1.89");
        assertFalse(EjectorLaw.fitted(5e6, 30e3, DISCHARGE, LOAD), "motive past 3500 kPa");
        assertFalse(EjectorLaw.fitted(.9e5, 30e3, .85e5, LOAD), "motive under 100 kPa");
    }

    // ---- The tile ----

    private static Flowsheet.Feed feed(double temperature, double pressure, String component, double flow, String other, double otherFlow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put(component, flow);
        if (otherFlow > 0) amounts.put(other, otherFlow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    private static Flowsheet.Feed load() {
        return feed(LOAD, SUCTION, FluidMaterialCatalog.NITROGEN, NITROGEN_FLOW, "Water", STEAM_FLOW);
    }

    /** The tile on the load, its Motive port free or wired to {@code motive}. */
    private static Flowsheet sheet(Flowsheet.Feed motive) {
        var units = new ArrayList<Flowsheet.Unit>();
        var streams = new ArrayList<Flowsheet.Stream>();
        units.add(new Flowsheet.Unit("J", "ejector", "J-101", 0, 0, new Params(JsonParser.parseString("{\"discharge\":" + DISCHARGE + ",\"motive\":" + MOTIVE + "}").getAsJsonObject())));
        streams.add(new Flowsheet.Stream("load", "load", null, new Flowsheet.End("J", EjectorUnit.SUCTION), load()));
        if (motive != null) streams.add(new Flowsheet.Stream("steam", "steam", null, new Flowsheet.End("J", EjectorUnit.MOTIVE), motive));
        streams.add(new Flowsheet.Stream("out", "out", new Flowsheet.End("J", EjectorUnit.DISCHARGE), null, null));
        return new Flowsheet(new Flowsheet.Basis(PACKAGE, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    private static FlowsheetRun run(Flowsheet sheet) {
        return FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
    }

    /** Steady, and what entered left: the flash's own closure. */
    private static FlowsheetRun closes(FlowsheetRun run) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-9 * Math.max(1, run.massIn()), "mass");
        double scale = Math.max(Math.max(Math.abs(run.heatIn()), Math.abs(run.heatOut())), run.duties());
        assertEquals(0, run.heatGap(), 1e-6 * Math.max(1, scale), "energy");
        return run;
    }

    private static double reading(FlowsheetRun run, String key) {
        var readings = run.reports().get("J").readings();
        assertTrue(readings.containsKey(key), () -> key + " in " + readings);
        return readings.get(key);
    }

    private static boolean noted(FlowsheetRun run, String key) {
        return run.reports().get("J").notes().stream().anyMatch(note -> note.key().equals(key));
    }

    /** The motive the law needs for the load, kg/s, from a free port's run. */
    private static double need() {
        return reading(closes(on(() -> run(sheet(null)))), "need");
    }

    /** {@code share} of the need, wired: steam at the door's pressure, superheated a little. */
    private static Flowsheet wired(double share) {
        double waterMass = on(() -> EjectorUnit.waterMass(THERMO));
        return sheet(feed(460, MOTIVE, "Water", share * need() / waterMass, "Water", 0));
    }

    private static Rating rating(Flowsheet sheet, FlowsheetRun run) {
        return on(() -> SheetSizing.rate(sheet, run, THERMO)).units().get("J").rating();
    }

    @Test void aFreeMotivePortIsAUtilityAndTheTileCloses() {
        var run = closes(on(() -> run(sheet(null))));
        var load = run.streams().get("load");
        var out = run.streams().get("out");
        double molarMass = 1e3 * load.mass() / load.total();
        double need = EjectorLaw.ratio(MOTIVE, SUCTION, DISCHARGE, load.temperature(), molarMass) * load.mass();
        assertEquals(need, reading(run, "need"), 1e-9 * need, "the law's need");
        assertEquals(need, reading(run, "motive"), 1e-9 * need, "the utility gives the need");
        assertEquals(DISCHARGE, out.pressure(), 0, "flashed at the discharge pressure");
        double waterMass = on(() -> EjectorUnit.waterMass(THERMO));
        for (int i = 0; i < AXIS.size(); i++)
            assertEquals(load.amounts()[i] + (i == WATER ? need / waterMass : 0), out.amounts()[i], 1e-9 * out.total(), AXIS.get(i) + ": the load and the motive leave together");
        assertEquals(load.mass() + need, out.mass(), 1e-9 * out.mass());
        assertFalse(noted(run, "ejector.motive_short"));
        assertTrue(out.temperature() > LOAD, "the steam warms the load: " + out.temperature());
    }

    @Test void aWiredMotiveUnderTheNeedIsRedAndWarns() {
        var sheet = wired(.9);
        var run = closes(on(() -> run(sheet)));
        assertTrue(noted(run, "ejector.motive_short"));
        var rating = rating(sheet, run);
        assertEquals(Lamp.RED, rating.reading(Limit.EJECTOR_MOTIVE).lamp());
        assertEquals(Lamp.RED, rating.lamp());
        var out = run.streams().get("out");
        var steam = run.streams().get("steam");
        var load = run.streams().get("load");
        for (int i = 0; i < AXIS.size(); i++) assertEquals(load.amounts()[i] + steam.amounts()[i], out.amounts()[i], 1e-9 * out.total(), AXIS.get(i));
    }

    @Test void aWiredMotiveBetweenTheNeedAndItsSpareIsYellow() {
        var sheet = wired(1.1);
        var run = closes(on(() -> run(sheet)));
        assertFalse(noted(run, "ejector.motive_short"));
        var rating = rating(sheet, run);
        assertEquals(Lamp.GREEN, rating.reading(Limit.EJECTOR_MOTIVE).lamp());
        assertEquals(Lamp.YELLOW, rating.reading(Limit.EJECTOR_MOTIVE_SPARE).lamp());
        var held = wired(1.3);
        var spare = rating(held, closes(on(() -> run(held))));
        assertEquals(Lamp.GREEN, spare.reading(Limit.EJECTOR_MOTIVE).lamp());
        assertEquals(Lamp.GREEN, spare.reading(Limit.EJECTOR_MOTIVE_SPARE).lamp());
    }

    @Test void aWiredMotiveThatIsNotSteamWarns() {
        double waterMass = on(() -> EjectorUnit.waterMass(THERMO));
        var sheet = sheet(feed(460, MOTIVE, "Water", need() / waterMass, FluidMaterialCatalog.NITROGEN, need() / waterMass));
        var run = closes(on(() -> run(sheet)));
        assertTrue(noted(run, "ejector.motive_water"));
    }

    @Test void aFreePortReadsNoMotiveRowAndAutosSuctionHoldsItsSpare() {
        var sheet = sheet(null);
        var run = closes(on(() -> run(sheet)));
        var sized = on(() -> SheetSizing.rate(sheet, run, THERMO)).units().get("J");
        assertTrue(sized.rating().reading(Limit.EJECTOR_MOTIVE) == null && sized.rating().reading(Limit.EJECTOR_MOTIVE_SPARE) == null, "a utility gives the need");
        var size = (EjectorSizing.Size) sized.size();
        double flow = run.streams().get("load").state().volume() * DesignBasis.DEFAULT.designFlow() * 3600, held = flow * (1 + Limit.AUTO_SPARE.value() / 100);
        double speed = Limit.VACUUM_SPEED.value(SUCTION / 1e5);
        double passes = Math.PI / 4 * size.suction() * size.suction() * speed * 3600, smaller = size.suction() - .05;
        assertTrue(passes >= held, () -> size + " passes " + passes + " m³/h of the " + held + " held");
        assertTrue(Math.PI / 4 * smaller * smaller * speed * 3600 < held, "the smallest that holds the spare");
        assertTrue(UnitSize.built(size) != null, "the constructor builds it");
        assertEquals(Lamp.GREEN, sized.rating().reading(Limit.EJECTOR_STAGES).lamp(), "200 mbar: one stage");
        assertEquals(Lamp.GREEN, sized.rating().reading(Limit.EJECTOR_COMPRESSION).lamp(), "a compression of 6");
    }
}

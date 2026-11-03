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
 * Main plan W10's and W10b's gate (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.12): the vacuum set's law S1
 * reproduces the section's table and cases ({@code tools/process-simulator/vacuum-set-law.js}); the tile closes mass, energy and components on a free and a wired Motive port; a wired motive under
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
    /** Section 3.12's table: motive 1000 kPa, discharge 120 kPa; the tile's load at 50 °C, the cases' vent at 40 °C. */
    private static final double MOTIVE = 1e6, DISCHARGE = 1.2e5, LOAD = 323.15, VENT = 313.15;
    /** The load: nitrogen with a little steam, at 20 kPa, mol/s. */
    private static final double SUCTION = 2e4, NITROGEN_FLOW = 10, STEAM_FLOW = .5;

    private static <T> T on(Supplier<T> test) {
        return MaterialRuntime.with(CATALOG, PACKAGE, test::get);
    }

    /** S1 on one kilogram of air, from {@code suction} kPa. */
    private static double air(double suction) {
        return EjectorLaw.steam(1, 0, MOTIVE, suction * 1e3, DISCHARGE, 0);
    }

    @Test void theSetLawReproducesSection312sTable() {
        double[] suctions = {80, 50, 30, 20, 12, 5, 2};
        double[] table = {.65, 1.40, 2.22, 2.87, 3.68, 5.08, 6.55};
        for (int i = 0; i < suctions.length; i++) assertEquals(table[i], Math.round(air(suctions[i]) * 100) / 100., 1e-9, suctions[i] + " kPa");
        // k: El-Dessouky's air at 30 -> 120 kPa, 2.21 kg/kg over ln 4.
        assertEquals(2.21 / Math.log(4), EjectorLaw.K, .01);
        // The vendors' rule: a kilogram of hydrogen counts 3.8 of air, of M 100 0.54, of water 1.269.
        assertEquals(3.8, Math.round(10 * EjectorLaw.asAir(1, 2)) / 10., 1e-9);
        assertEquals(.54, Math.round(100 * EjectorLaw.asAir(1, 100)) / 100., 1e-9);
        assertEquals(1.269, Math.round(1000 * EjectorLaw.asAir(1, EjectorLaw.WATER)) / 1000., 1e-9);
    }

    @Test void theWaterTermReadsTheWorldsVapourPressure() {
        // The plan's tool took Antoine's 7.358 kPa at 40 °C; the world's Wagner-Pruss reads within a few parts in a thousand.
        double psat = on(() -> MODEL.saturationPressure(VENT)), condensing = EjectorLaw.CONDENSING * psat;
        assertEquals(7.358e3, psat, .01 * 7.358e3);
        System.out.printf("world psat at 40 °C %.4f kPa, P_c %.3f kPa (Antoine 7.358, 14.72)%n", psat / 1e3, condensing / 1e3);
        assertEquals(0, EjectorLaw.steam(0, 1, MOTIVE, 20e3, DISCHARGE, condensing), 0, "above P_c the condenser has taken the water");
        assertEquals(EjectorLaw.K * Math.sqrt(29 / EjectorLaw.WATER) * Math.log(condensing / 5e3), EjectorLaw.steam(0, 1, MOTIVE, 5e3, DISCHARGE, condensing), 1e-12,
                "below P_c the water is lifted to it, not to the discharge");
    }

    @Test void theMotiveFactorIsPcfOverItsValueAt10Bar() {
        double[] bars = {3, 5, 7, 10, 15, 20, 35};
        double[] factors = {1.35, 1.22, 1.12, 1.00, .93, .93, .93};
        for (int i = 0; i < bars.length; i++) assertEquals(factors[i], Math.round(100 * EjectorLaw.motiveFactor(bars[i] * 1e5)) / 100., 1e-9, bars[i] + " bar");
        assertEquals(EjectorLaw.motiveFactor(1.5e6), EjectorLaw.motiveFactor(5e6), 0, "flat above 15 bar");
        double below = EjectorLaw.motiveFactor(1.5e6 - 1), at = EjectorLaw.motiveFactor(1.5e6), above = EjectorLaw.motiveFactor(1.5e6 + 1);
        assertEquals(0, at - below, 1e-9, "zero slope at the edge");
        assertEquals(0, above - at, 0);
    }

    @Test void theSetLawReproducesSection312sCases() {
        double psat = on(() -> MODEL.saturationPressure(VENT)), condensing = EjectorLaw.CONDENSING * psat;
        // {gas kg/h, M, suction kPa, water kg/h or NaN for the gas saturated with water at 40 °C, the plan's steam kg/h}
        double[][] cases = {{50, 28, 30, Double.NaN, 113}, {100, 29, 80, Double.NaN, 65}, {500, 30, 10.6, Double.NaN, 2360}, {500, 30, 2, 1e4, 43700}};
        for (var c : cases) {
            double suction = c[2] * 1e3, water = Double.isNaN(c[3]) ? c[0] / c[1] * psat / (suction - psat) * EjectorLaw.WATER : c[3];
            double steam = EjectorLaw.steam(EjectorLaw.asAir(c[0], c[1]), water, MOTIVE, suction, DISCHARGE, condensing);
            System.out.printf("%.1f kPa: water %.0f kg/h, steam %.0f kg/h against the plan's %.0f%n", c[2], water, steam, c[4]);
            assertEquals(c[4], steam, .02 * c[4], c[2] + " kPa: Antoine against the world's vapour pressure");
        }
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
        int nitrogen = AXIS.indexOf(FluidMaterialCatalog.NITROGEN);
        double[] weights = MODEL.molecularWeights();
        double condensing = EjectorLaw.CONDENSING * on(() -> MODEL.saturationPressure(load.temperature()));
        assertTrue(condensing > SUCTION, "at 50 °C the load's water is lifted to P_c, not to the discharge");
        double need = EjectorLaw.steam(EjectorLaw.asAir(load.amounts()[nitrogen] * weights[nitrogen], 1e3 * weights[nitrogen]), load.amounts()[WATER] * weights[WATER], MOTIVE, SUCTION, DISCHARGE, condensing);
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
    }
}

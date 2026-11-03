package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W2 gate of batch 2026-11-02-process-simulator: every unit closes mass and energy on the world's flash, and the pump and the
 * compressor are the world's closures.
 */
final class FlowsheetUnitsTest {
    private static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, PACKAGE).components();
    private static final int WATER = AXIS.size() - 1;
    private static final double[] WET_CRUDE = wetCrude();

    private static double[] wetCrude() {
        var crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), AXIS.size());
        crude[WATER] = .2;
        return crude;
    }

    private static double[] only(String id) {
        double[] amounts = new double[AXIS.size()];
        amounts[AXIS.indexOf(id)] = 1;
        return amounts;
    }

    private static final double[] PURE_WATER = only("Water"), NITROGEN = only(FluidMaterialCatalog.NITROGEN);
    private static final double[] DRY_CRUDE = dry();

    private static double[] dry() {
        double[] crude = WET_CRUDE.clone();
        crude[WATER] = 0;
        return crude;
    }

    private static Flowsheet.Feed feed(double temperature, double pressure, double[] amounts, double flow) {
        var ids = new LinkedHashMap<String, Double>();
        double total = Arrays.stream(amounts).sum();
        for (int i = 0; i < amounts.length; i++) if (amounts[i] > 0) ids.put(AXIS.get(i), amounts[i] * flow / total);
        return new Flowsheet.Feed(temperature, pressure, ids, List.of());
    }

    /** A sheet by hand, as the file holds it. */
    private static final class Sheet {
        private final List<Flowsheet.Unit> units = new ArrayList<>();
        private final List<Flowsheet.Stream> streams = new ArrayList<>();

        Sheet unit(String id, String type, String settings) {
            units.add(new Flowsheet.Unit(id, type, id, 0, 0, new Params(JsonParser.parseString(settings).getAsJsonObject())));
            return this;
        }

        Sheet feed(String id, String unit, String port, Flowsheet.Feed feed) {
            streams.add(new Flowsheet.Stream(id, id, null, new Flowsheet.End(unit, port), feed));
            return this;
        }

        Sheet line(String id, String from, String fromPort, String to, String toPort) {
            streams.add(new Flowsheet.Stream(id, id, new Flowsheet.End(from, fromPort), new Flowsheet.End(to, toPort), null));
            return this;
        }

        Sheet product(String id, String from, String port) {
            streams.add(new Flowsheet.Stream(id, id, new Flowsheet.End(from, port), null, null));
            return this;
        }

        FlowsheetRun run() {
            var basis = new Flowsheet.Basis(PACKAGE, "test", AXIS);
            return FlowsheetEngine.run(new Flowsheet(basis, units, streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
            });
        }
    }

    private static Sheet sheet() {
        return new Sheet();
    }

    /** Steady, and what entered left: the flash's own closure, far under the run's tolerance. */
    private static FlowsheetRun closes(FlowsheetRun run) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-9 * Math.max(1, run.massIn()), "mass");
        double scale = Math.max(Math.max(Math.abs(run.heatIn()), Math.abs(run.heatOut())), run.duties());
        assertEquals(0, run.heatGap(), 1e-6 * Math.max(1, scale), "energy");
        return run;
    }

    private static double reading(FlowsheetRun run, String calculation, String key) {
        var readings = run.reports().get(calculation).readings();
        assertTrue(readings.containsKey(key), () -> key + " in " + readings);
        return readings.get(key);
    }

    private static boolean logged(FlowsheetRun run, String key) {
        return run.log().stream().anyMatch(note -> note.key().equals(key)) || run.reports().values().stream().flatMap(report -> report.notes().stream()).anyMatch(note -> note.key().equals(key));
    }

    private static double[] sum(FlowsheetRun run, String... streams) {
        double[] total = new double[AXIS.size()];
        for (var id : streams) {
            double[] amounts = run.streams().get(id).amounts();
            for (int i = 0; i < total.length; i++) total[i] += amounts[i];
        }
        return total;
    }

    private static void sameAmounts(double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], actual[i], 1e-9 * Math.max(1, expected[i]), AXIS.get(i));
    }

    @Test void aValveKeepsItsEnthalpy() {
        var run = closes(sheet().unit("V", "valve", "{\"target\":\"drop\",\"drop\":2e5}")
                .feed("in", "V", "a", feed(420, 8e5, WET_CRUDE, 100)).product("out", "V", "b").run());
        assertEquals(6e5, run.streams().get("out").pressure(), 1e-6);
        assertEquals(run.streams().get("in").enthalpy(), run.streams().get("out").enthalpy(), 1e-6 * Math.abs(run.streams().get("in").enthalpy()));
        assertEquals(2e5, reading(run, "V", "drop"), 1e-6);
        assertTrue(reading(run, "V", "cooled") > 0, "a flash that makes vapour cools");
    }

    @Test void aValveCannotRaiseThePressure() {
        var run = sheet().unit("V", "valve", "{\"pressure\":9e5}").feed("in", "V", "a", feed(420, 8e5, WET_CRUDE, 100)).product("out", "V", "b").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "valve.outlet"), () -> "log " + run.log());
    }

    @Test void aMixerAddsAndTakesTheLowestPressure() {
        var run = closes(sheet().unit("M", "mixer", "{}")
                .feed("hot", "M", "in/1", feed(360, 4e5, PURE_WATER, 100)).feed("cold", "M", "in/2", feed(300, 3e5, PURE_WATER, 100)).product("out", "M", "out").run());
        var out = run.streams().get("out");
        assertEquals(3e5, out.pressure(), 1e-6);
        assertEquals(330, out.temperature(), 1, "equal flows of water end near the middle");
        sameAmounts(sum(run, "hot", "cold"), out.amounts());
        assertTrue(logged(run, "mixer.pressures"), "unequal inlets warn");
    }

    @Test void aSplitterKeepsTheStateAndOneOutletTakesTheRest() {
        var run = closes(sheet().unit("S", "splitter", "{\"outlets\":[{\"id\":\"a\",\"share\":0.3},{\"id\":\"b\"}]}")
                .feed("in", "S", "in", feed(420, 8e5, WET_CRUDE, 100)).product("a", "S", "out/a").product("b", "S", "out/b").run());
        assertEquals(30, run.streams().get("a").total(), 1e-9);
        assertEquals(70, run.streams().get("b").total(), 1e-9);
        assertEquals(run.streams().get("in").temperature(), run.streams().get("a").temperature(), 1e-6);
        assertEquals(run.streams().get("in").temperature(), run.streams().get("b").temperature(), 1e-6);
        assertEquals(.7, reading(run, "S", "rest"), 1e-12);
    }

    @Test void aSplitterTakesFlowsAndRefusesMoreThanArrives() {
        var flows = closes(sheet().unit("S", "splitter", "{\"mode\":\"flows\",\"outlets\":[{\"id\":\"a\",\"flow\":25},{\"id\":\"b\"}]}")
                .feed("in", "S", "in", feed(420, 8e5, WET_CRUDE, 100)).product("a", "S", "out/a").product("b", "S", "out/b").run());
        assertEquals(25, flows.streams().get("a").total(), 1e-9);
        var over = sheet().unit("S", "splitter", "{\"mode\":\"flows\",\"outlets\":[{\"id\":\"a\",\"flow\":125},{\"id\":\"b\"}]}")
                .feed("in", "S", "in", feed(420, 8e5, WET_CRUDE, 100)).product("a", "S", "out/a").product("b", "S", "out/b").run();
        assertEquals(FlowsheetRun.Status.FAILED, over.status());
        assertTrue(logged(over, "splitter.over"), () -> "log " + over.log());
    }

    @Test void aFilterTakesTheSolidsAsCake() {
        var slurry = new Flowsheet.Feed(320, 3e5, feed(320, 3e5, PURE_WATER, 100).amounts(), List.of(new Flowsheet.Solid("icheme:demo_particle", "100", .5)));
        var run = closes(sheet().unit("F", "filter", "{}").feed("in", "F", "a", slurry).product("out", "F", "b").run());
        var in = run.streams().get("in");
        var out = run.streams().get("out");
        assertEquals(.5, in.solids().massKg(), 1e-12);
        assertTrue(out.solids().empty());
        assertEquals(in.mass() - .5, out.mass(), 1e-9);
        assertEquals(in.temperature(), out.temperature(), 1e-3, "the cake leaves at the stream's temperature");
        assertEquals(.5, reading(run, "F", "solids"), 1e-12);
    }

    @Test void aDrumGivesOneOutletPerPhase() {
        var run = closes(sheet().unit("D", "drum", "{\"drop\":1e4}")
                .feed("in", "D", "in", feed(400, 2.1e5, WET_CRUDE, 100)).product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        sameAmounts(run.streams().get("in").amounts(), sum(run, "gas", "oil", "water"));
        assertEquals(2e5, run.streams().get("oil").pressure(), 1e-6);
        assertTrue(run.streams().get("gas").total() > 0 && run.streams().get("oil").total() > 0, "this feed splits");
        double[] oil = run.streams().get("oil").amounts();
        assertTrue(oil[WATER] < .01 * Arrays.stream(oil).sum(), "water leaves the oil");
    }

    @Test void aDryDrumLetsItsGasCarryTheSolids() {
        var dusty = new Flowsheet.Feed(320, 3e5, feed(320, 3e5, NITROGEN, 100).amounts(), List.of(new Flowsheet.Solid("icheme:demo_particle", "100", .5)));
        var run = closes(sheet().unit("D", "drum", "{}").feed("in", "D", "in", dusty).product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertEquals(.5, run.streams().get("gas").solids().massKg(), 1e-12, "no liquid is there to take them");
    }

    @Test void aSlurryDrumLeavesItsSolidsInTheLiquid() {
        var slurry = new Flowsheet.Feed(300, 3e5, feed(300, 3e5, PURE_WATER, 100).amounts(), List.of(new Flowsheet.Solid("icheme:demo_particle", "100", .5)));
        var run = closes(sheet().unit("D", "drum", "{}").feed("in", "D", "in", slurry).product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertEquals(.5, run.streams().get("water").solids().massKg(), 1e-12);
    }

    @Test void aDrumHeatsToATemperatureOrByADuty() {
        var set = closes(sheet().unit("D", "drum", "{\"pressure_mode\":\"set\",\"pressure\":2e5,\"heat\":\"temperature\",\"temperature\":450}")
                .feed("in", "D", "in", feed(350, 3e5, WET_CRUDE, 100)).product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertEquals(450, reading(set, "D", "temperature"), 1e-9);
        assertEquals(2e5, reading(set, "D", "pressure"), 1e-6);
        double duty = reading(set, "D", "duty");
        var back = closes(sheet().unit("D", "drum", "{\"pressure_mode\":\"set\",\"pressure\":2e5,\"heat\":\"duty\",\"duty\":" + duty + "}")
                .feed("in", "D", "in", feed(350, 3e5, WET_CRUDE, 100)).product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertEquals(450, reading(back, "D", "temperature"), 1e-3, "the same duty reaches the same temperature");
    }

    @Test void aStorageTileHoldsTheHigherOfAmbientAndItsBubblePressure() {
        var water = closes(sheet().unit("T", "storage_tank", "{}")
                .feed("in", "T", "in", feed(300, 5e5, PURE_WATER, 100)).product("gas", "T", "gas").product("oil", "T", "oil").product("water", "T", "water").run());
        assertEquals(101325, reading(water, "T", "pressure"), 1e-6, "a floating roof holds ambient");
        var light = closes(sheet().unit("T", "storage_tank", "{}")
                .feed("in", "T", "in", feed(300, 2e7, WET_CRUDE, 100)).product("gas", "T", "gas").product("oil", "T", "oil").product("water", "T", "water").run());
        double held = reading(light, "T", "pressure");
        assertTrue(held > 101325 && held < 2e7, "a sphere holds its bubble pressure, read " + held);
        assertTrue(reading(light, "T", "vapour") < 1e-2, "at its bubble point the tank holds liquid");
    }

    /** Water both ways, so the answer is a textbook's: U = 1000 W/m²K from two 2000 films, Q / (U LMTD). */
    private static final String HOT_WATER = "{\"target\":\"outlet\",\"side\":\"tube\",\"temperature\":320}";

    private static Sheet waterPair(String settings) {
        return sheet().unit("E", "exchanger", settings).feed("hot", "E", "tube_a", feed(360, 5e5, PURE_WATER, 100)).feed("cold", "E", "shell_a", feed(300, 5e5, PURE_WATER, 200))
                .product("hot out", "E", "tube_b").product("cold out", "E", "shell_b");
    }

    @Test void anExchangerSetByOneSidePassesTheDutyToTheOther() {
        var run = closes(waterPair(HOT_WATER).run());
        var hot = run.streams().get("hot");
        var hotOut = run.streams().get("hot out");
        var coldOut = run.streams().get("cold out");
        assertEquals(320, hotOut.temperature(), 1e-9);
        assertEquals(hot.enthalpy() - hotOut.enthalpy(), coldOut.enthalpy() - run.streams().get("cold").enthalpy(), 1e-6 * Math.abs(hot.enthalpy()), "both sides book one duty");
        assertEquals(320, coldOut.temperature(), .5, "twice the flow rises half as far");
        assertTrue(run.loops().isEmpty(), "two calculations and a link make no loop");
    }

    @Test void aRatingFindsTheTextbookSurface() {
        var run = waterPair(HOT_WATER).run();
        double duty = reading(run, "E/tube", "duty");
        double lmtd = (40 - 20) / Math.log(40 / 20.);
        assertEquals(duty / (1000 * lmtd), reading(run, "E/rating", "area_needed"), .02 * duty / (1000 * lmtd));
        assertEquals(20, reading(run, "E/rating", "approach"), .5);
    }

    @Test void anAreaGivesBackTheDutyThatAskedForIt() {
        var byOutlet = waterPair(HOT_WATER).run();
        double area = reading(byOutlet, "E/rating", "area_needed");
        var byArea = closes(waterPair("{\"target\":\"area\",\"area\":" + area + "}").run());
        assertEquals(320, byArea.streams().get("hot out").temperature(), 1e-3);
        assertEquals(reading(byOutlet, "E/tube", "duty"), reading(byArea, "E", "duty"), 1e-3 * reading(byOutlet, "E/tube", "duty"));
    }

    @Test void aHeatCrossWarnsAndStopsNothing() {
        var run = closes(waterPair("{\"target\":\"outlet\",\"side\":\"tube\",\"temperature\":310}").run());
        assertEquals(310, run.streams().get("hot out").temperature(), 1e-9);
        assertTrue(reading(run, "E/rating", "approach") > 0, "this one still has room");
        var cross = closes(waterPair("{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":370}").run());
        assertTrue(logged(cross, "exchanger.heat_cross"), () -> "cold cannot leave above the hot inlet: log " + cross.log());
        assertEquals(370, cross.streams().get("cold out").temperature(), 1e-9, "the law still holds the balance, and the rating says what is wrong");
    }

    @Test void aFreeSideIsAUtility() {
        var run = closes(sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":340,\"shell_drop\":1e4}")
                .feed("in", "E", "shell_a", feed(300, 5e5, PURE_WATER, 100)).product("out", "E", "shell_b").run());
        assertEquals(340, run.streams().get("out").temperature(), 1e-9);
        assertEquals(4.9e5, run.streams().get("out").pressure(), 1e-6);
        assertTrue(run.duties() > 0, "the utility's heat is booked");
    }

    @Test void aKettleBoilsItsFeedToAGasAndALiquid() {
        var run = closes(sheet().unit("K", "kettle", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":420}")
                .feed("heating", "K", "tube_a", feed(520, 30e5, PURE_WATER, 2000)).feed("feed", "K", "feed", feed(390, 2e5, WET_CRUDE, 100))
                .product("heating out", "K", "tube_b").product("gas", "K", "gas").product("liquid", "K", "liquid").run());
        sameAmounts(run.streams().get("feed").amounts(), sum(run, "gas", "liquid"));
        assertTrue(run.streams().get("gas").total() > 0 && run.streams().get("liquid").total() > 0);
        assertTrue(run.streams().get("heating out").temperature() < 520);
    }

    @Test void anAreaNeedsBothSides() {
        var run = sheet().unit("E", "exchanger", "{\"target\":\"area\",\"area\":10}").feed("in", "E", "shell_a", feed(300, 5e5, PURE_WATER, 100)).product("out", "E", "shell_b").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "exchanger.area_pair"), () -> "log " + run.log());
    }

    @Test void theSetSideNeedsItsStream() {
        var run = sheet().unit("E", "exchanger", "{\"side\":\"tube\",\"temperature\":340}").feed("in", "E", "shell_a", feed(300, 5e5, PURE_WATER, 100)).product("out", "E", "shell_b").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "exchanger.set_side"), () -> "log " + run.log());
    }

    @Test void anAirCoolerRemovesHeatAndCannotAddIt() {
        var run = closes(sheet().unit("A", "cooler", "{\"temperature\":320,\"drop\":1e4}")
                .feed("in", "A", "a", feed(380, 3e5, PURE_WATER, 100)).product("out", "A", "b").run());
        assertEquals(320, run.streams().get("out").temperature(), 1e-9);
        assertEquals(run.streams().get("in").enthalpy() - run.streams().get("out").enthalpy(), reading(run, "A", "duty"), 1e-3);
        var heats = closes(sheet().unit("A", "cooler", "{\"temperature\":400}")
                .feed("in", "A", "a", feed(380, 3e5, PURE_WATER, 100)).product("out", "A", "b").run());
        assertEquals(380, heats.streams().get("out").temperature(), 1e-3, "the stream passes");
        assertTrue(logged(heats, "cooler.heats"));
    }

    /**
     * C2: air and tower water come in at 25 °C, so a Cooler's outlet or duty target below it stops there and says so, as does an
     * exchanger's set side against a free side; a free side that heats stays as it is.
     */
    @Test void aCoolerStopsAtItsCoolant() {
        var outlet = closes(sheet().unit("A", "cooler", "{\"temperature\":283.15}")
                .feed("in", "A", "a", feed(360, 3e5, PURE_WATER, 100)).product("out", "A", "b").run());
        assertEquals(298.15, outlet.streams().get("out").temperature(), 1e-9);
        assertTrue(logged(outlet, "cooler.coolant"));
        var duty = closes(sheet().unit("A", "cooler", "{\"target\":\"duty\",\"duty\":1e9}")
                .feed("in", "A", "a", feed(360, 3e5, PURE_WATER, 100)).product("out", "A", "b").run());
        assertEquals(298.15, duty.streams().get("out").temperature(), 1e-9);
        assertEquals(duty.streams().get("in").enthalpy() - duty.streams().get("out").enthalpy(), reading(duty, "A", "duty"), 1e-3);
        var held = closes(sheet().unit("A", "cooler", "{\"temperature\":303.15}")
                .feed("in", "A", "a", feed(360, 3e5, PURE_WATER, 100)).product("out", "A", "b").run());
        assertEquals(303.15, held.streams().get("out").temperature(), 1e-9);
        assertFalse(logged(held, "cooler.coolant"), "30 °C stands");
        var free = closes(sheet().unit("E", "exchanger", "{\"side\":\"shell\",\"temperature\":283.15}")
                .feed("in", "E", "shell_a", feed(360, 3e5, PURE_WATER, 100)).product("out", "E", "shell_b").run());
        assertEquals(298.15, free.streams().get("out").temperature(), 1e-9);
        assertTrue(logged(free, "cooler.coolant"));
        var heated = closes(sheet().unit("E", "exchanger", "{\"side\":\"shell\",\"temperature\":283.15}")
                .feed("in", "E", "shell_a", feed(270, 3e5, NITROGEN, 100)).product("out", "E", "shell_b").run());
        assertEquals(283.15, heated.streams().get("out").temperature(), 1e-9, "a free side that heats is out of scope");
        assertFalse(logged(heated, "cooler.coolant"));
    }

    @Test void aFiredHeaterTakesBothDropsAndStopsAtItsLimit() {
        var free = closes(sheet().unit("H", "fired_heater", "{\"temperature\":640,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}")
                .feed("in", "H", "a", feed(500, 30e5, WET_CRUDE, 100)).product("out", "H", "b").run());
        assertEquals(30e5 - 7.6e5, free.streams().get("out").pressure(), 1e-6);
        assertEquals(640, free.streams().get("out").temperature(), 1e-9);
        double duty = reading(free, "H", "duty");
        var limited = closes(sheet().unit("H", "fired_heater", "{\"temperature\":640,\"limit\":" + duty / 2 + "}")
                .feed("in", "H", "a", feed(500, 30e5, WET_CRUDE, 100)).product("out", "H", "b").run());
        assertEquals(duty / 2, reading(limited, "H", "duty"), 1e-3 * duty);
        assertTrue(limited.streams().get("out").temperature() < 640);
        assertTrue(logged(limited, "heater.limit"));
        var cools = closes(sheet().unit("H", "fired_heater", "{\"temperature\":400}")
                .feed("in", "H", "a", feed(500, 30e5, WET_CRUDE, 100)).product("out", "H", "b").run());
        assertEquals(500, cools.streams().get("out").temperature(), 1e-3, "with the fire out the stream passes");
        assertTrue(logged(cools, "heater.cools"));
    }

    @Test void aHeatersCoilsApartShareItsLimit() {
        String coils = "{\"coils\":\"apart\",\"convection_temperature\":600,\"temperature\":640";
        var free = closes(sheet().unit("H", "fired_heater", coils + "}").feed("steam", "H", "convection_a", feed(460, 10e5, PURE_WATER, 100)).feed("crude", "H", "radiant_a", feed(500, 30e5, WET_CRUDE, 100))
                .product("hot steam", "H", "convection_b").product("hot crude", "H", "radiant_b").run());
        assertEquals(600, free.streams().get("hot steam").temperature(), 1e-9);
        assertEquals(640, free.streams().get("hot crude").temperature(), 1e-9);
        double steam = reading(free, "H/convection", "duty"), crude = reading(free, "H/radiant", "duty");
        var limited = closes(sheet().unit("H", "fired_heater", coils + ",\"limit\":" + (steam + crude / 2) + "}").feed("steam", "H", "convection_a", feed(460, 10e5, PURE_WATER, 100)).feed("crude", "H", "radiant_a", feed(500, 30e5, WET_CRUDE, 100))
                .product("hot steam", "H", "convection_b").product("hot crude", "H", "radiant_b").run());
        assertEquals(600, limited.streams().get("hot steam").temperature(), 1e-9, "the convection coil takes its share first");
        assertEquals(crude / 2, reading(limited, "H/radiant", "duty"), 1e-3 * crude);
    }

    @Test void aPumpIsTheWorldsClosure() {
        var run = closes(sheet().unit("P", "pump", "{\"target\":\"rise\",\"rise\":8e5}")
                .feed("in", "P", "in", feed(300, 2e5, PURE_WATER, 100)).product("out", "P", "out").run());
        var in = run.streams().get("in");
        var out = run.streams().get("out");
        double density = in.mass() / in.state().volume();
        assertEquals(new FlowControl.Pump(1, 1).power(in.mass(), 8e5, density, 2e5), reading(run, "P", "power"), 1e-9);
        assertEquals(in.mass() / density * 8e5, reading(run, "P", "power"), 1e-6, "q v head");
        assertEquals(in.enthalpy() + reading(run, "P", "power"), out.enthalpy(), 1e-4, "booked as heat into the discharge");
        assertEquals(10e5, out.pressure(), 1e-6);
        assertEquals(8e5 / (density * 9.80665), reading(run, "P", "head"), 1e-9);
        assertFalse(logged(run, "pump.bubble_point"), "cold water is far from boiling");
    }

    @Test void aPumpRefusesVapourAndWarnsAtTheBubblePoint() {
        var gas = sheet().unit("P", "pump", "{\"pressure\":8e5}").feed("in", "P", "in", feed(300, 2e5, NITROGEN, 100)).product("out", "P", "out").run();
        assertEquals(FlowsheetRun.Status.FAILED, gas.status());
        assertTrue(logged(gas, "pump.phase"), () -> "log " + gas.log());
        var drum = closes(sheet().unit("D", "drum", "{}").unit("P", "pump", "{\"pressure\":8e5}")
                .feed("in", "D", "in", feed(400, 2e5, DRY_CRUDE, 100)).product("gas", "D", "gas").product("water", "D", "water")
                .line("oil", "D", "oil", "P", "in").product("out", "P", "out").run());
        assertTrue(logged(drum, "pump.bubble_point"), "a dry drum's oil stands at its bubble point");
        var wet = closes(sheet().unit("D", "drum", "{}").unit("P", "pump", "{\"pressure\":8e5}")
                .feed("in", "D", "in", feed(400, 2e5, WET_CRUDE, 100)).product("gas", "D", "gas").product("water", "D", "water")
                .line("oil", "D", "oil", "P", "in").product("out", "P", "out").run());
        assertFalse(logged(wet, "pump.bubble_point"), "steam carries part of a wet drum's pressure, so its oil stands below its own bubble pressure");
    }

    @Test void aCompressorIsTheWorldsClosure() {
        var run = closes(sheet().unit("C", "compressor", "{\"pressure\":5e5}")
                .feed("in", "C", "in", feed(300, 1e5, NITROGEN, 100)).product("out", "C", "out").run());
        var in = run.streams().get("in");
        double density = in.mass() / in.state().volume();
        assertEquals(new FlowControl.Compressor(1, 2).power(in.mass(), 4e5, density, 1e5), reading(run, "C", "power"), 1e-9);
        assertEquals(100 * 8.314462618 * 300 * Math.log(5), reading(run, "C", "power"), .005 * reading(run, "C", "power"), "nRT ln r for a near-ideal gas");
        assertEquals(in.enthalpy() + reading(run, "C", "power"), run.streams().get("out").enthalpy(), 1e-3);
    }

    @Test void aCompressorRefusesLiquid() {
        var run = sheet().unit("C", "compressor", "{\"pressure\":5e5}").feed("in", "C", "in", feed(300, 1e5, PURE_WATER, 100)).product("out", "C", "out").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "compressor.phase"), () -> "log " + run.log());
    }

    @Test void feedAndEffluentAroundAHeaterIsNoLoop() {
        var run = closes(sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":480}").unit("H", "fired_heater", "{\"temperature\":560}")
                .feed("cold", "E", "shell_a", feed(320, 100e5, PURE_WATER, 100)).line("warm", "E", "shell_b", "H", "a").line("hot", "H", "b", "E", "tube_a").product("cooled", "E", "tube_b").run());
        assertTrue(run.loops().isEmpty(), "the set side solves first, so the heat loop is no stream loop");
        double taken = run.streams().get("warm").enthalpy() - run.streams().get("cold").enthalpy();
        assertEquals(run.streams().get("hot").enthalpy() - taken, run.streams().get("cooled").enthalpy(), .1, "the hot side gives back what the cold side took");
    }

    @Test void aBoilingStreamStaysOnItsPlateau() {
        var run = closes(sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":480}").unit("H", "fired_heater", "{\"temperature\":560}")
                .feed("cold", "E", "shell_a", feed(320, 20e5, PURE_WATER, 100)).line("warm", "E", "shell_b", "H", "a").line("hot", "H", "b", "E", "tube_a").product("cooled", "E", "tube_b").run());
        var cooled = run.streams().get("cooled");
        assertEquals(485.53, cooled.temperature(), .01, "water at 20 bar condenses at its one temperature");
        assertTrue(cooled.state().waterLiquid() > 0 && cooled.state().waterVapor() > 0, "part steam, part water");
    }

    @Test void aDrumOnABoilingPlateauKeepsItsEnergy() {
        var run = closes(sheet().unit("V", "valve", "{\"pressure\":5e5}").unit("D", "drum", "{}")
                .feed("in", "V", "a", feed(460, 20e5, PURE_WATER, 100)).line("flashed", "V", "b", "D", "in").product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertTrue(run.streams().get("gas").total() > 0 && run.streams().get("water").total() > 0, "a let-down flashes part of the water");
        assertEquals(run.streams().get("gas").temperature(), run.streams().get("water").temperature(), .01);
        assertEquals(0, run.streams().get("gas").state().waterLiquid(), .02 * run.streams().get("gas").total(), "the gas stays gas");
    }

    /** Wet crude across its three-phase zone: the engine's PH answers 393.36 K here, and the world's TP flash holds the true state. */
    @Test void aWetStreamStaysOnTheFlashCurve() {
        for (double temperature : new double[]{370, 376, 379, 382, 385}) {
            var run = closes(sheet().unit("V", "valve", "{\"target\":\"drop\",\"drop\":1}")
                    .feed("in", "V", "a", feed(temperature, 2e5, WET_CRUDE, 100)).product("out", "V", "b").run());
            assertEquals(temperature, run.streams().get("out").temperature(), .01, "a one-pascal let-down barely moves it");
            assertEquals(run.streams().get("in").state().waterLiquid(), run.streams().get("out").state().waterLiquid(), .05, "nor its free water");
        }
    }

    /** Cooled by a refrigerant's cold side, as no air or tower water can take water under 25 °C (C2). */
    @Test void waterColdEnoughToFreezeStaysALiquidStandIn() {
        var run = closes(sheet().unit("E", "exchanger", "{\"target\":\"duty\",\"side\":\"shell\",\"duty\":-1.2e6}")
                .feed("in", "E", "shell_a", feed(300, 5e5, PURE_WATER, 100)).product("out", "E", "shell_b")
                .feed("cold", "E", "tube_a", feed(110, 5e5, NITROGEN, 5000)).product("cold out", "E", "tube_b").run());
        assertTrue(run.streams().get("out").temperature() < 273, "no ice on a stream (D23): the energy still closes");
    }

    @Test void aRecycleThroughTheOtherSideCloses() {
        var run = closes(sheet().unit("M", "mixer", "{}").unit("E", "exchanger", "{\"target\":\"duty\",\"side\":\"shell\",\"duty\":1e5}").unit("S", "splitter", "{\"outlets\":[{\"id\":\"back\",\"share\":0.5},{\"id\":\"on\"}]}")
                .feed("feed", "M", "in/1", feed(330, 5e5, PURE_WATER, 100)).line("mixed", "M", "out", "E", "shell_a").line("hot", "E", "shell_b", "S", "in")
                .line("back", "S", "out/back", "M", "in/2").product("product", "S", "out/on").run());
        assertEquals(100, run.streams().get("product").total(), 1e-2, "at steady state what enters leaves");
        assertEquals(1, run.loops().size());
        assertTrue(run.loops().getFirst().closed());
    }

    @Test void whatCannotRunFailsWhereItStandsAndTheRestSolves() {
        var run = sheet().unit("V", "valve", "{\"pressure\":2e5}").unit("X", "gone_unit", "{}").unit("W", "valve", "{\"pressure\":1e5}")
                .feed("in", "V", "a", feed(420, 8e5, WET_CRUDE, 100)).line("a", "V", "b", "X", "in").line("b", "X", "out", "W", "a").product("c", "W", "b").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "unit.removed"), () -> "log " + run.log());
        assertEquals(FlowsheetRun.Outcome.SOLVED, run.outcomes().get("V"));
        assertEquals(FlowsheetRun.Outcome.FAILED, run.outcomes().get("X"));
        assertEquals(FlowsheetRun.Outcome.NOT_SOLVED, run.outcomes().get("W"));
        assertTrue(run.streams().containsKey("a") && !run.streams().containsKey("b"));
    }

    @Test void aFreeRequiredInletRefusesTheUnit() {
        var run = sheet().unit("V", "valve", "{\"pressure\":2e5}").product("out", "V", "b").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "unit.free_inlet"), () -> "log " + run.log());
    }

    @Test void aRemovedRowLeavesItsStreamOpen() {
        var run = sheet().unit("S", "splitter", "{\"outlets\":[{\"id\":\"a\",\"share\":0.3},{\"id\":\"b\"}]}")
                .feed("in", "S", "in", feed(420, 8e5, WET_CRUDE, 100)).product("a", "S", "out/a").product("b", "S", "out/b").product("old", "S", "out/gone").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "stream.open"), () -> "log " + run.log());
        assertTrue(run.streams().containsKey("a") && run.streams().containsKey("b"), "the rows that stand still solve");
    }

    @Test void aSettingOfTheWrongKindRefusesTheUnitAndNotTheSheet() {
        var run = sheet().unit("S", "splitter", "{\"outlets\":\"none\"}").feed("in", "S", "in", feed(420, 8e5, WET_CRUDE, 100)).product("a", "S", "out/a").run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertTrue(logged(run, "unit.setting"), () -> "log " + run.log());
    }

    @Test void aFreeOutletKeepsTheBalanceAndSaysSo() {
        var run = closes(sheet().unit("D", "drum", "{}").feed("in", "D", "in", feed(400, 2e5, WET_CRUDE, 100)).product("oil", "D", "oil").run());
        assertTrue(logged(run, "port.free_outlet"), "the gas leaves by an open port");
        assertEquals(run.massIn(), run.massOut(), 1e-9);
    }

    @Test void aRunNeedsNoRuntimeOfItsOwn() {
        assertEquals(PACKAGE, MaterialRuntime.with(CATALOG, PACKAGE, () -> closes(sheet().unit("V", "valve", "{\"pressure\":2e5}")
                .feed("in", "V", "a", feed(420, 8e5, WET_CRUDE, 100)).product("out", "V", "b").run()).streams().size() == 2 ? PACKAGE : ""), "a run nests inside one that stands");
    }
}

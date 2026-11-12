package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.flowsheet.unit.MoverUnit;
import com.wormzjl.icheme.science.flowsheet.unit.TurbineUnit;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.SteamTurbine;
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
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

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
        var crude = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.worldCrude();
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
            return run(com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts.NONE);
        }
        FlowsheetRun run(com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts facts) {
            var basis = new Flowsheet.Basis(PACKAGE, "test", AXIS);
            return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(new Flowsheet(basis, units, streams, FlowsheetSolver.Settings.DEFAULT).with(facts), CATALOG, () -> {
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

    /**
     * The world's diverter valve (batch 2026-11-09-batch-distiller), one inlet and three outlets, sends all its flow to the chosen
     * one. On the sheet it is the splitter: the chosen outlet takes the rest and the other two a share of none (FlowsheetCatalog).
     */
    @Test void aDiverterIsASplitterWhoseChosenOutletTakesAll() {
        var run = closes(sheet().unit("S", "splitter", "{\"outlets\":[{\"id\":\"a\",\"share\":0},{\"id\":\"b\"},{\"id\":\"c\",\"share\":0}]}")
                .feed("in", "S", "in", feed(420, 8e5, DRY_CRUDE, 100)).product("a", "S", "out/a").product("b", "S", "out/b").product("c", "S", "out/c").run());
        assertEquals(0, run.streams().get("a").total());
        assertEquals(100, run.streams().get("b").total(), 1e-9);
        assertEquals(0, run.streams().get("c").total());
        assertEquals(run.streams().get("in").temperature(), run.streams().get("b").temperature(), 1e-6);
        assertEquals(1, reading(run, "S", "rest"), 1e-12);
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

    @Test void aStorageTileHoldsAmbientUnderARoofAndItsBubblePressureInASphere() {
        var water = closes(sheet().unit("T", "storage_tank", "{}")
                .feed("in", "T", "in", feed(300, 5e5, PURE_WATER, 100)).product("gas", "T", "gas").product("oil", "T", "oil").product("water", "T", "water").run());
        assertEquals(101325, reading(water, "T", "pressure"), 1e-6, "a floating roof holds ambient");
        // W3B_NETWORK_PLAN.md section 44: a whole crude's bubble pressure at 300 K, 1.41 bar, is its dissolved methane and
        // ethane; at equal vapour and liquid volumes it pushes with 0.66 bar, so a roof stores it and its light ends leave as gas
        // (before, the tile held it unflashed at 1.41 bar, a sphere).
        var crude = closes(sheet().unit("T", "storage_tank", "{}")
                .feed("in", "T", "in", feed(300, 2e7, WET_CRUDE, 100)).product("gas", "T", "gas").product("oil", "T", "oil").product("water", "T", "water").run());
        assertEquals(101325, reading(crude, "T", "pressure"), 1e-6, "a whole crude stands under a roof");
        assertTrue(crude.streams().get("gas").total() > 0, "its light ends leave by the Gas outlet");
        double[] lpg = only("Propane");
        lpg[AXIS.indexOf("N-butane")] = 1;
        var light = closes(sheet().unit("T", "storage_tank", "{}")
                .feed("in", "T", "in", feed(300, 2e6, lpg, 100)).product("gas", "T", "gas").product("oil", "T", "oil").product("water", "T", "water").run());
        double held = reading(light, "T", "pressure");
        assertTrue(held > 3e5 && held < 2e6, "a sphere holds propane and butane at their bubble pressure, read " + held);
        assertTrue(reading(light, "T", "vapour") < 1e-2, "at its bubble point the tank holds liquid");
    }

    /**
     * W3B_NETWORK_PLAN.md section 44, on the liquids of a 3 bar drum at 340 K (wet crude with nitrogen): the water holds a
     * trace of dissolved gas, which puts its bubble pressure at the drum's 3 bar and its storage pressure (equal vapour and
     * liquid volumes) at 0.32 bar, so a roof stores it at ambient and the gas leaves by the Gas outlet; the oil's is 1.05
     * bar, a sphere's, held unflashed at the pressure it arrives at.
     */
    @Test void aStorageTileDegassesWhatARoofStoresAndHoldsTheRestUnflashed() {
        double[] feed = DRY_CRUDE.clone();
        double dry = Arrays.stream(feed).sum();
        for (int i = 0; i < feed.length; i++) feed[i] *= 40 / dry;
        feed[WATER] = 12;
        feed[AXIS.indexOf(FluidMaterialCatalog.NITROGEN)] = 4;
        var run = closes(sheet().unit("D", "drum", "{\"pressure_mode\":\"inlet\"}").unit("TW", "storage_tank", "{}").unit("TO", "storage_tank", "{}")
                .feed("in", "D", "in", feed(340, 3e5, feed, 56)).product("gas", "D", "gas").line("water", "D", "water", "TW", "in").line("oil", "D", "oil", "TO", "in")
                .product("wg", "TW", "gas").product("wo", "TW", "oil").product("ww", "TW", "water").product("og", "TO", "gas").product("oo", "TO", "oil").product("ow", "TO", "water").run());
        assertEquals(101325, reading(run, "TW", "pressure"), 1e-6, "a floating roof holds ambient");
        assertEquals(3.14e-4, run.streams().get("wg").total(), .02 * 3.14e-4, "what the water held dissolved leaves as gas, mol/s");
        assertEquals(3e5, reading(run, "TO", "pressure"), 1e-6, "a sphere holds the oil at the pressure it arrives at, its bubble pressure");
        assertTrue(reading(run, "TO", "vapour") < 1e-4, "unflashed");
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

    @Test void crossflowAreaAndOutletSpecificationsUseTheSamePhysicalRouting() {
        var size=new com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing.Size(false,1,1,3,com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW);
        var facts=com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts.NONE.with("E",com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts.UnitFacts.AUTO.with(size));
        var outlet=closes(waterPair(HOT_WATER).run(facts));
        double area=reading(outlet,"E/rating","area_needed");
        assertTrue(area>reading(waterPair(HOT_WATER).run(),"E/rating","area_needed"));
        var rated=closes(waterPair("{\"target\":\"area\",\"area\":"+area+"}").run(facts));
        assertEquals(320,rated.streams().get("hot out").temperature(),.005);
        assertEquals(reading(outlet,"E/tube","duty"),reading(rated,"E","duty"),.001*reading(outlet,"E/tube","duty"));
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
        // W8 form A (batch 2026-11-03-rotating-machinery, D42): the world's ideal work over the design efficiency at the duty, all of it into the discharge.
        assertEquals(new FlowControl.Pump(1, 1).power(in.mass(), 8e5, density, 2e5, 1.4), reading(run, "P", "hydraulic"), 1e-9);
        assertEquals(in.mass() / density * 8e5, reading(run, "P", "hydraulic"), 1e-6, "q v head");
        double eta = CentrifugalPump.rated(in.mass() / density, 8e5 / (density * 9.80665)).efficiency();
        assertEquals(eta, reading(run, "P", "efficiency"), 1e-15);
        assertEquals(reading(run, "P", "hydraulic") / eta, reading(run, "P", "power"), 1e-9, "shaft power");
        assertEquals(in.enthalpy() + reading(run, "P", "power"), out.enthalpy(), 1e-4, "the shaft power booked as heat into the discharge");
        assertEquals(10e5, out.pressure(), 1e-6);
        assertEquals(8e5 / (density * 9.80665), reading(run, "P", "head"), 1e-9);
        assertFalse(logged(run, "pump.bubble_point"), "cold water is far from boiling");
    }

    /** The world's pump takes gas since request 11 of batch 2026-11-03-rotating-machinery (owner decisions D19, D20): its
     * set-flow law moves the mixture, so the sheet's pump on nitrogen closes. It still warns at the bubble point. */
    @Test void aPumpTakesGasAndWarnsAtTheBubblePoint() {
        var gas = closes(sheet().unit("P", "pump", "{\"pressure\":8e5}").feed("in", "P", "in", feed(300, 2e5, NITROGEN, 100)).product("out", "P", "out").run());
        assertFalse(logged(gas, "pump.phase"), () -> "log " + gas.log());
        var drum = closes(sheet().unit("D", "drum", "{}").unit("P", "pump", "{\"pressure\":8e5}")
                .feed("in", "D", "in", feed(400, 2e5, DRY_CRUDE, 100)).product("gas", "D", "gas").product("water", "D", "water")
                .line("oil", "D", "oil", "P", "in").product("out", "P", "out").run());
        assertTrue(logged(drum, "pump.bubble_point"), "a dry drum's oil stands at its bubble point");
        var wet = closes(sheet().unit("D", "drum", "{}").unit("P", "pump", "{\"pressure\":8e5}")
                .feed("in", "D", "in", feed(400, 2e5, WET_CRUDE, 100)).product("gas", "D", "gas").product("water", "D", "water")
                .line("oil", "D", "oil", "P", "in").product("out", "P", "out").run());
        // W3b: the oil of a wet drum holds the water it dissolves, so it stands at its own bubble point as a dry drum's does
        // (before, the free water left the oil dry and the steam carried part of the pressure).
        assertTrue(logged(wet, "pump.bubble_point"), "a wet drum's oil stands at its bubble point too");
    }

    @Test void aCompressorIsTheWorldsClosure() {
        var run = closes(sheet().unit("C", "compressor", "{\"pressure\":5e5}")
                .feed("in", "C", "in", feed(300, 1e5, NITROGEN, 100)).product("out", "C", "out").run());
        var in = run.streams().get("in");
        double density = in.mass() / in.state().volume();
        double k = FluidThermodynamics.forNetwork(CATALOG, PACKAGE).heatCapacityRatio(in.state());
        assertEquals(new FlowControl.Compressor(1, 2).power(in.mass(), 4e5, density, 1e5, k), reading(run, "C", "power"), 1e-9);
        // The world's compressor is polytropic at 78 % since batch 2026-11-03-rotating-machinery (plan section 4.4): for a
        // near-ideal gas n R T σ (r^(1/σ) − 1)/η with σ = η k/(k − 1), about 700 kW here against 401 kW for nRT ln r.
        double eta = .78, sigma = eta * k / (k - 1);
        assertEquals(100 * 8.314462618 * 300 * sigma * (Math.pow(5, 1 / sigma) - 1) / eta, reading(run, "C", "power"), .005 * reading(run, "C", "power"), "polytropic work for a near-ideal gas");
        assertEquals(in.enthalpy() + reading(run, "C", "power"), run.streams().get("out").enthalpy(), 1e-3);
        // W8: the closure's power is already the shaft power at 78 %; the tile proposes a 2-pole motor with 20 % spare.
        assertEquals(.78, reading(run, "C", "efficiency"), 0);
        double motor = Motor.ratingFor(reading(run, "C", "power"));
        assertEquals(motor, reading(run, "C", "motor"), 0);
        assertTrue(motor >= 1.2 * reading(run, "C", "power"));
        assertEquals(reading(run, "C", "power") / Motor.efficiency(motor, 2), reading(run, "C", "electric"), 1e-6);
    }

    /** W8's worked pump (batch 2026-11-03-rotating-machinery, W8_BUILD_PLAN.md section 2): water, 100 m³/h at 80 m head. */
    @Test void aPumpProposesItsMotor() {
        double flow = 100 / 3600., hydraulic = 1000 * 9.80665 * flow * 80, eta = MoverUnit.pumpEfficiency(flow);
        assertEquals(21.79e3, hydraulic, 10);
        assertEquals(.7016, eta, 1e-4);
        assertEquals(CentrifugalPump.rated(flow, 80).efficiency(), eta, 0);
        double shaft = hydraulic / eta, motor = Motor.ratingFor(shaft), electric = Motor.electricPower(shaft, motor, 2, false);
        assertEquals(31.06e3, shaft, 10);
        assertEquals(45e3, motor, 0);
        assertEquals(.940, Motor.efficiency(motor, 2), 1e-12);
        assertEquals(33.04e3, electric, 10);
        assertEquals(661, electric / (20 * 2.5), .5, "FE/t at 2.5 J/FE");
        // The tile on water at 25 °C, 100 m³/h: the same law on the run's own density.
        double density = 997.05, moles = 100 / 3600. * density / .01801528;
        var run = closes(sheet().unit("P", "pump", "{\"target\":\"rise\",\"rise\":" + 80 * density * 9.80665 + "}")
                .feed("in", "P", "in", feed(298.15, 2e5, PURE_WATER, moles)).product("out", "P", "out").run());
        var in = run.streams().get("in");
        double q = in.mass() / (in.mass() / in.state().volume());
        assertEquals(flow, q, .01 * flow);
        assertEquals(.7016, reading(run, "P", "efficiency"), 2e-3);
        assertEquals(31.06e3, reading(run, "P", "power"), .01 * 31.06e3);
        assertEquals(45e3, reading(run, "P", "motor"), 0);
        assertEquals(.940, reading(run, "P", "motor_efficiency"), 1e-12);
        assertEquals(reading(run, "P", "power") / .94, reading(run, "P", "electric"), 1e-6);
        double warmer = run.streams().get("out").temperature() - in.temperature();
        // The ideal work v Δp raises the liquid's enthalpy at nearly its temperature: (∂h/∂p)_T = v (1 − αT), αT = 0.0766 for water
        // at 25 °C. So the losses, and αT of the ideal work, warm it: 0.094 K here, where the ideal work alone warmed it by 0.014 K.
        double losses = reading(run, "P", "power") - reading(run, "P", "hydraulic"), expansion = .0766 * reading(run, "P", "hydraulic");
        assertEquals((losses + expansion) / (in.mass() * 4181), warmer, .03 * warmer, "the whole shaft power stays in the water");
    }

    /** W8's steam turbine: 40 bar and 400 °C steam to 4 bar, the plan's case of {@code SteamTurbine.isentropicDrop}. */
    @Test void aSteamTurbineTakesItsWorkOutOfTheSteam() {
        double kilograms = 2, moles = kilograms / .01801528;
        var run = closes(sheet().unit("ST", "steam_turbine", "{\"exhaust\":4e5}")
                .feed("in", "ST", "in", feed(673.15, 40e5, PURE_WATER, moles)).product("out", "ST", "out").run());
        var in = run.streams().get("in");
        var out = run.streams().get("out");
        double power = reading(run, "ST", "power"), eta = reading(run, "ST", "efficiency"), drop = reading(run, "ST", "drop");
        System.out.printf(java.util.Locale.ROOT, "turbine: drop %.1f kJ/kg, eta %.4f, power %.1f kW, steam rate %.2f kg/kWh, exhaust %.2f K%n", drop / 1e3, eta, power / 1e3,
                reading(run, "ST", "steam_rate"), out.temperature());
        assertEquals(524e3, drop, .03 * 524e3, "the isentropic drop on the inlet's real P/ρ");
        assertEquals(SteamTurbine.typicalEfficiency(power), eta, 1e-9, "a design rated at its own shaft power");
        assertEquals(in.mass() * drop * eta, power, 1e-6 * power);
        assertEquals(3600 * in.mass() / (power / 1e3), reading(run, "ST", "steam_rate"), 1e-9);
        assertEquals(4e5, out.pressure(), 0);
        assertEquals(in.enthalpy() - power, out.enthalpy(), 1e-3, "the work leaves the steam");
        assertEquals(-power, run.reports().get("ST").duty(), 1e-9);
        var uphill = sheet().unit("ST", "steam_turbine", "{\"exhaust\":50e5}").feed("in", "ST", "in", feed(673.15, 40e5, PURE_WATER, moles)).product("out", "ST", "out").run();
        assertEquals(FlowsheetRun.Status.FAILED, uphill.status());
        assertTrue(logged(uphill, "turbine.drop"), () -> "log " + uphill.log());
    }

    @Test void aTurbineDesignSettlesOnItsOwnRating() {
        for (double isentropic : new double[]{1e4, 3e5, 1e6, 4.2e6, 1e8}) {
            var design = TurbineUnit.designed(isentropic);
            assertEquals(SteamTurbine.typicalEfficiency(design.efficiency() * isentropic), design.efficiency(), 1e-12, "at " + isentropic);
            assertEquals(design.efficiency() * isentropic, design.ratedPower(), 1e-9 * isentropic);
        }
        assertEquals(.45, TurbineUnit.designed(1e5).efficiency(), 0, "single stage");
        assertEquals(.76, TurbineUnit.designed(1e7).efficiency(), 0, "multistage");
    }

    /** The world's compressor takes liquid since request 11 of batch 2026-11-03-rotating-machinery (owner decisions D19,
     * D20): its set-flow law moves the mixture, so the sheet's compressor on water closes; the sizing stage reads the
     * liquid share (the SHOULD row {@code COMPRESSOR_LIQUID}). */
    @Test void aCompressorTakesLiquid() {
        var run = closes(sheet().unit("C", "compressor", "{\"pressure\":5e5}").feed("in", "C", "in", feed(300, 1e5, PURE_WATER, 100)).product("out", "C", "out").run());
        assertFalse(logged(run, "compressor.phase"), () -> "log " + run.log());
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
        // W3b: the cubic's water condenses 0.10 K over IAPWS's 485.53 K at 20 bar.
        assertEquals(485.53, cooled.temperature(), .15, "water at 20 bar condenses at its one temperature");
        assertTrue(TestPhases.waterLiquid(cooled.state()) > 0 && TestPhases.waterVapor(cooled.state()) > 0, "part steam, part water");
    }

    @Test void aDrumOnABoilingPlateauKeepsItsEnergy() {
        var run = closes(sheet().unit("V", "valve", "{\"pressure\":5e5}").unit("D", "drum", "{}")
                .feed("in", "V", "a", feed(460, 20e5, PURE_WATER, 100)).line("flashed", "V", "b", "D", "in").product("gas", "D", "gas").product("oil", "D", "oil").product("water", "D", "water").run());
        assertTrue(run.streams().get("gas").total() > 0 && run.streams().get("water").total() > 0, "a let-down flashes part of the water");
        assertEquals(run.streams().get("gas").temperature(), run.streams().get("water").temperature(), .01);
        assertEquals(0, TestPhases.waterLiquid(run.streams().get("gas").state()), .02 * run.streams().get("gas").total(), "the gas stays gas");
    }

    /** Wet crude across its three-phase zone: the engine's PH answers 393.36 K here, and the world's TP flash holds the true state. */
    @Test void aWetStreamStaysOnTheFlashCurve() {
        for (double temperature : new double[]{370, 376, 379, 382, 385}) {
            var run = closes(sheet().unit("V", "valve", "{\"target\":\"drop\",\"drop\":1}")
                    .feed("in", "V", "a", feed(temperature, 2e5, WET_CRUDE, 100)).product("out", "V", "b").run());
            assertEquals(temperature, run.streams().get("out").temperature(), .01, "a one-pascal let-down barely moves it");
            assertEquals(TestPhases.waterLiquid(run.streams().get("in").state()), TestPhases.waterLiquid(run.streams().get("out").state()), .05, "nor its free water");
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

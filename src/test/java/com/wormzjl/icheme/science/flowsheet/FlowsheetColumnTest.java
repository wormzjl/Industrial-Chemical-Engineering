package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.operation.ColumnSolve;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnResult;
import com.wormzjl.icheme.science.column.v3.V3InitializationOptions;
import com.wormzjl.icheme.science.column.v3.V3NeuralRegistry;
import com.wormzjl.icheme.science.column.v3.V3SolveControl;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The W3 gate of batch 2026-11-02-process-simulator: the block is the calculator to the last digit; the design's sample closes in
 * two passes with a small gap; the furnace on a fixed duty and a 15 % recycle close.
 */
final class FlowsheetColumnTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    /**
     * The Tia Juana column, the test-only fixture of batch 2026-11-04-open-thermo-data (O3): these gates pin the simulator's coupling
     * to the column, and the classical start does not close the medium grade at every feed temperature they reach
     * (CRUDE_GRADES_APPLY_REVIEW.md). The door carries the assay as a label, so the sheet runs on the bundled catalog.
     */
    private static final V3ColumnInput BASE = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog().presets().column("tia_juana")
            .input(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog());
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();
    private static final double FEED_TRAY = BASE.topPressurePascal() + (BASE.feedStageNumber() - 1) * BASE.stagePressureDropPascal();

    private static final class Sheet {
        private final List<Flowsheet.Unit> units = new ArrayList<>();
        private final List<Flowsheet.Stream> streams = new ArrayList<>();

        Sheet unit(String id, String type, Params params) {
            units.add(new Flowsheet.Unit(id, type, id, 0, 0, params));
            return this;
        }

        Sheet unit(String id, String type, String settings) {
            return unit(id, type, new Params(JsonParser.parseString(settings).getAsJsonObject()));
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

        Sheet without(String stream) {
            assertTrue(streams.removeIf(line -> line.id().equals(stream)), stream);
            return this;
        }

        Sheet estimate(String stream, Flowsheet.Feed estimate) {
            for (int i = 0; i < streams.size(); i++) {
                var line = streams.get(i);
                if (line.id().equals(stream)) streams.set(i, new Flowsheet.Stream(line.id(), line.name(), line.from(), line.to(), estimate));
            }
            return this;
        }

        FlowsheetRun run() {
            return run(null);
        }

        FlowsheetRun run(FlowsheetRun last) {
            return com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(new Flowsheet(new Flowsheet.Basis(NETWORK, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT), CATALOG, () -> {
            }, last);
        }
    }

    private static Flowsheet.Feed crude(double temperature, double pressure) {
        var ids = BASE.componentBasis().componentIds();
        var amounts = new LinkedHashMap<String, Double>();
        double[] flows = BASE.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    private static Flowsheet.Feed water(double temperature, double pressure, double flow) {
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Water", flow);
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    /** The default preset's door, with each of the sample's cold sides at 0.3 bar. */
    private static Params door(ColumnComponents.Condenser condenser) {
        var door = ColumnDoor.from(BASE, condenser);
        door.json().addProperty("condenser_drop", .3e5);
        for (var row : door.rows("pumparounds")) row.json().addProperty("drop", .3e5);
        return door;
    }

    private static Sheet alone(double feedTray) {
        var sheet = new Sheet().unit("T", "column", ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER)).feed("feed", "T", "feed", crude(BASE.feedTemperatureKelvin(), feedTray))
                .product("overhead", "T", "overhead").product("distillate", "T", "distillate").product("water", "T", "water").product("bottoms", "T", "bottoms");
        int row = 1;
        for (var ignored : BASE.sideDraws()) {
            sheet.product("draw " + row, "T", "draw/" + row);
            row++;
        }
        return sheet;
    }

    private static V3ColumnResult calculator() {
        return calculator(BASE);
    }

    private static V3ColumnResult calculator(V3ColumnInput input) {
        var options = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST, V3InitializationOptions.WetStart.AUTO, 16, V3InitializationOptions.DEFAULT_BUDGET_POLLS, V3InitializationOptions.Correction.PROGRESS);
        var outcome = MaterialRuntime.with(CATALOG, input.packageId(), () -> V3ColumnCalculator.calculate(input, V3SolveControl.UNBOUNDED, 0, 0, options, V3NeuralRegistry.bundled().bind(CATALOG, input), V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        return ((V3ColumnOutcome.Success) outcome).result();
    }

    @Test void theBlockIsTheCalculatorToTheLastDigit() {
        var direct = calculator();
        var run = alone(feedTray(direct)).run();
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(direct.streams().size(), direct.streams().stream().filter(s -> run.streams().containsKey(name(s.streamId()))).count(), "every product is on the sheet");
        for (var stream : direct.streams()) {
            var out = run.streams().get(name(stream.streamId()));
            assertEquals(stream.temperatureKelvin(), out.temperature(), 0, stream.streamId());
            assertEquals(stream.pressurePascal(), out.pressure(), 0, stream.streamId());
            double[] amounts = out.amounts();
            for (var fraction : stream.moleFractions()) assertEquals(fraction.moleFraction() * stream.molarFlowMolPerSecond(), amounts[AXIS.indexOf(fraction.componentId())], 0, fraction.componentId());
        }
        var ledger = direct.dutyLedger().orElseThrow();
        assertEquals(-ledger.condenserWatts(), run.reports().get("T").readings().get("condenser"), 0);
        assertEquals(ledger.reboilerWatts(), run.reports().get("T").readings().get("reboiler"), 0);
        assertEquals(run.reports().get("T").readings().get("energy_gap"), run.heatGap(), 1, "free sides book their heat: what is left is what the two thermo models differ by");
    }

    /**
     * C2 ({@code COOLER_MODEL_PLAN.md} section 6): an air-then-water condenser with a 35 °C receiver reads its air's share and its
     * water's, which sum to its duty; the water's is the world's trim ({@link ColumnSolve#trim}) on the calculator's own column, the
     * overhead from the 40 °C split to the receiver. Its free water side warns of nothing at 35 °C.
     */
    @Test void anAirThenWaterCondenserSplitsItsDutyAsTheWorldDoes() {
        double receiver = 308.15;
        var params = ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_THEN_WATER);
        params.json().addProperty("condenser_temperature", receiver);
        var input = ColumnDoor.of(params).input(CATALOG.packages().get(BASE.packageId()), BASE.feedComponentMolarFlowsMolPerSecond(), BASE.feedTemperatureKelvin());
        var direct = calculator(input);
        var sheet = new Sheet().unit("T", "column", params).feed("feed", "T", "feed", crude(BASE.feedTemperatureKelvin(), feedTray(direct)))
                .product("overhead", "T", "overhead").product("distillate", "T", "distillate").product("water", "T", "water").product("bottoms", "T", "bottoms");
        for (int row = 1; row <= BASE.sideDraws().size(); row++) sheet.product("draw " + row, "T", "draw/" + row);
        var run = sheet.run();
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        var readings = run.reports().get("T").readings();
        double whole = readings.get("condenser"), air = readings.get("condenser_air"), water = readings.get("condenser_water");
        assertEquals(whole, air + water, 1e-12 * whole, "the two shares are the duty");
        assertTrue(water > 0 && water < air, () -> "the trim takes a small share: " + readings);
        var model = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
        double world = -MaterialRuntime.with(CATALOG, NETWORK, () -> ColumnSolve.trim(model, direct.inspection().orElseThrow(), input.componentBasis(), AXIS, receiver, -whole, () -> {
        }));
        assertEquals(world, water, 1e-9 * whole, "the world's share on the same column");
        assertFalse(run.reports().get("T").notes().stream().anyMatch(note -> note.key().equals("column.condenser_coolant")));
    }

    private static double feedTray(V3ColumnResult result) {
        return result.inspection().orElseThrow().nodes().get(BASE.feedStageNumber()).pressurePascal();
    }

    @Test void aFeedAtAnotherPressureIsFlashedToItsTray() {
        var run = alone(1e6).run();
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        var readings = run.reports().get("T").readings();
        assertTrue(readings.get("feed_temperature") < run.streams().get("feed").temperature(), "a let-down to the tray flashes and cools");
        assertTrue(Math.abs(readings.get("energy_gap")) < 2e-3 * readings.get("condenser"), () -> "the block keeps the stream's energy: " + readings);
    }

    private static String name(String id) {
        return switch (id) {
            case "overhead_vapor" -> "overhead";
            case "distillate_liquid" -> "distillate";
            case "free_water" -> "water";
            case "bottoms_liquid" -> "bottoms";
            default -> {
                int tray = Integer.parseInt(id.substring("side_liquid_tray_".length())), row = 1;
                for (var draw : BASE.sideDraws()) {
                    if (draw.trayNumber() == tray) yield "draw " + row;
                    row++;
                }
                throw new IllegalArgumentException(id);
            }
        };
    }

    /** The design's sample: the crude through a pump, the three pumparound coolers and the residue exchanger to a furnace and a valve. */
    private static Sheet sample(String furnace) {
        return new Sheet().unit("P-101", "pump", "{\"pressure\":14e5}").unit("T-101", "column", door(ColumnComponents.Condenser.EXCHANGER))
                .unit("E-101", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":473.15,\"tube_drop\":0.5e5,\"shell_drop\":0.3e5}")
                .unit("H-101", "fired_heater", furnace).unit("V-101", "valve", "{\"pressure\":2.72e5}").unit("E-102", "cooler", "{\"temperature\":343.15,\"drop\":0.2e5}").unit("P-102", "pump", "{\"pressure\":6e5}")
                .line("S-1", "P-101", "out", "T-101", "pa/1/a").line("S-2", "T-101", "pa/1/b", "T-101", "pa/2/a").line("S-3", "T-101", "pa/2/b", "T-101", "pa/3/a")
                .line("S-4", "T-101", "pa/3/b", "E-101", "tube_a").line("S-5", "E-101", "tube_b", "H-101", "a").line("S-6", "H-101", "b", "V-101", "a").line("S-7", "V-101", "b", "T-101", "feed")
                .feed("STEAM", "T-101", "steam/1", water(BASE.steamFeeds().getFirst().temperatureKelvin(), 3.5e5, BASE.steamFeeds().getFirst().molarFlowMolPerSecond()))
                .feed("CWS", "T-101", "condenser_a", water(303.15, 4e5, 190000 / 3.6)).product("CWR", "T-101", "condenser_b")
                .product("OFFGAS", "T-101", "overhead").product("NAPHTHA", "T-101", "distillate").product("WATER", "T-101", "water").product("KERO", "T-101", "draw/1").line("S-8", "T-101", "draw/2", "P-102", "in")
                .product("DIESEL", "P-102", "out").product("AGO", "T-101", "draw/3").line("S-9", "T-101", "bottoms", "E-101", "shell_a").line("S-10", "E-101", "shell_b", "E-102", "a");
    }

    private static final String HOLDS = "{\"temperature\":638.15,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}";

    private static double heatScale(FlowsheetRun run) {
        return run.duties() + run.reports().get("T-101").readings().get("condenser") + BASE.pumparounds().stream().mapToDouble(p -> Math.abs(p.dutyWatts())).sum();
    }

    private static void closed(FlowsheetRun run, double heatShare) {
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(0, run.massGap(), 1e-3 * run.massIn(), "mass");
        assertTrue(Math.abs(run.heatGap()) < heatShare * heatScale(run), () -> "heat gap " + run.heatGap() + " of " + heatScale(run));
    }

    @Test void theSampleClosesInTwoPasses() {
        var run = sample(HOLDS).feed("CRUDE", "P-101", "in", crude(298.15, 8e5)).product("RESIDUE", "E-102", "b").run();
        closed(run, 5e-4);
        assertEquals(1, run.loops().size(), "one loop stays, the residue's");
        assertEquals(2, run.loops().getFirst().passes());
        assertEquals(List.of("S-9"), run.loops().getFirst().tears(), "it tears at the column's bottoms");
        assertEquals(638.15, run.streams().get("S-6").temperature(), 1e-9);
        assertEquals(473.15, run.streams().get("S-10").temperature(), 1e-9);
        assertTrue(run.streams().get("S-1").temperature() < run.streams().get("S-2").temperature(), "the first pumparound heats the crude");
        assertTrue(run.streams().get("S-4").temperature() < run.streams().get("S-5").temperature(), "the residue heats it further");
        assertEquals(run.reports().get("T-101").readings().get("condenser"), run.streams().get("CWR").enthalpy() - run.streams().get("CWS").enthalpy(), 1, "the cooling water takes the condenser's duty");
        for (var report : run.reports().values()) for (var note : report.notes()) assertFalse(note.key().startsWith("column.pa_hot") || note.key().equals("column.condenser_hot"), () -> "a sound sheet: " + note);
    }

    /**
     * PA 2 on cooling water of its own: the water takes the duty the door sets, at the flow the column sets for it, 10 K up from 30 °C
     * (COOLER_MODEL_PLAN.md section 8); the crude arrives colder, and the furnace that makes it up runs into its limit and says so.
     */
    @Test void aPumparoundOnAStreamOfItsOwnTakesItsDutyFromIt() {
        var run = sample("{\"coils\":\"series\",\"target\":\"outlet\",\"temperature\":638.15,\"limit\":90e6,\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}")
                .without("S-2").without("S-3").line("S-2", "T-101", "pa/1/b", "T-101", "pa/3/a")
                .feed("CW", "T-101", "pa/2/a", water(303.15, 4e5, 16000)).product("CW back", "T-101", "pa/2/b")
                .feed("CRUDE", "P-101", "in", crude(298.15, 8e5)).product("RESIDUE", "E-102", "b").run();
        closed(run, 5e-4);
        var in = run.streams().get("CW");
        var out = run.streams().get("CW back");
        assertEquals(428.4, in.mass(), .1, "kg/s");
        assertEquals(in.mass(), out.mass(), 1e-9, "the water stays on its side");
        assertEquals(40 + 273.15, out.temperature(), 1e-3);
        assertEquals(Math.abs(BASE.pumparounds().get(1).dutyWatts()), out.enthalpy() - in.enthalpy(), 1e3, "the duty as set");
        // V3 values its steam by temperature: the steam valve's 1.2 K of Joule-Thomson cooling is some 14 kW the two models differ by (U1b).
        assertTrue(Math.abs(run.heatGap()) < 65e3, () -> "heat gap " + run.heatGap());
        assertEquals(102.4 + 273.15, run.streams().get("S-4").temperature(), .05, "the crude reaches E-101 two coolers warm, not three");
        var limit = run.reports().get("H-101").notes().stream().filter(note -> note.key().equals("heater.limit")).findFirst().orElseThrow();
        // 98.08 MW since the column took the network's water (E30 of batch 2026-11-04-open-thermo-data): 16 kW less, from the
        // products' small re-split.
        assertEquals(98.08e6, Double.parseDouble(limit.arguments().getFirst()), .01e6, "what the furnace asks");
        assertEquals(90e6, Double.parseDouble(limit.arguments().get(1)), 1, "and what it may give");
    }

    @Test void aFurnaceOnAFixedDutyCloses() {
        var held = sample(HOLDS).feed("CRUDE", "P-101", "in", crude(298.15, 8e5)).product("RESIDUE", "E-102", "b").run();
        double duty = held.reports().get("H-101").readings().get("duty") - 8e6;
        var run = onDuty(duty).run(held);
        closed(run, 1e-3);
        assertTrue(run.streams().get("S-6").temperature() < 638.15, "8 MW less fire leaves the coil cooler");
        assertTrue(run.loops().getFirst().closed());
        assertTrue(run.loops().getFirst().passes() > 2, "now the column's feed moves with the residue");
    }

    private static Sheet onDuty(double duty) {
        return sample("{\"target\":\"duty\",\"duty\":" + duty + ",\"radiant_drop\":5.1e5,\"convection_drop\":2.5e5}").feed("CRUDE", "P-101", "in", crude(298.15, 8e5)).product("RESIDUE", "E-102", "b");
    }

    /**
     * With no flow on its tear the residue's heat is missing from the first pass, so the feed comes in tens of megawatts short (568 K
     * against the closed loop's 609 K, 21 MW less feed enthalpy). That is a colder column, not an unsound one: it passes its audit
     * with liquid on every tray (E30 of batch 2026-11-04-open-thermo-data), so the cold run closes on the state a first estimate of the
     * residue reaches.
     */
    @Test void aFurnaceOnADutyClosesColdAsFromAFirstEstimate() {
        var cold = onDuty(70.4e6).run();
        closed(cold, 1e-3);
        assertTrue(cold.loops().getFirst().closed());
        assertTrue(cold.passes().getFirst().steps().stream().noneMatch(FlowsheetRun.Step::failed), () -> "the cold first pass solves: " + cold.passes().getFirst());
        var bottoms = crude(573, 2.7e5);
        var amounts = new LinkedHashMap<String, Double>();
        var heavy = BASE.componentBasis().componentIds();
        amounts.put(heavy.getLast(), 220.);
        var run = onDuty(70.4e6).estimate("S-9", new Flowsheet.Feed(bottoms.temperature(), bottoms.pressure(), amounts, List.of())).run();
        closed(run, 1e-3);
        assertTrue(run.loops().getFirst().closed());
        // Each run's tear closed to the sheet's tolerances, so the two answers differ by at most twice them.
        var tolerance = FlowsheetSolver.Settings.DEFAULT;
        for (var product : List.of("OFFGAS", "NAPHTHA", "WATER", "KERO", "DIESEL", "AGO", "RESIDUE")) {
            var a = cold.streams().get(product);
            var b = run.streams().get(product);
            assertEquals(b.mass(), a.mass(), 2 * tolerance.flowTolerance() * Math.max(a.mass(), b.mass()), product + " kg/s");
            assertEquals(b.temperature(), a.temperature(), 2 * tolerance.temperatureTolerance(), product + " K");
        }
    }

    @Test void aFifteenPercentRecycleCloses() {
        var run = sample(HOLDS).unit("M-1", "mixer", "{}").unit("S-1R", "splitter", "{\"outlets\":[{\"id\":\"back\",\"share\":0.15},{\"id\":\"on\"}]}")
                .feed("CRUDE", "M-1", "in/1", crude(298.15, 8e5)).line("MIXED", "M-1", "out", "P-101", "in").line("S-11", "E-102", "b", "S-1R", "in")
                .line("BACK", "S-1R", "out/back", "M-1", "in/2").product("RESIDUE", "S-1R", "out/on").run();
        closed(run, 2e-3);
        assertTrue(run.loops().stream().allMatch(FlowsheetRun.Loop::closed));
        assertTrue(run.streams().get("BACK").total() > 0);
    }

    /** The kettle's tube side gives the door's duty; too little steam ends colder than the bottoms, and the check says so. */
    @Test void aKettleTakesItsDutyFromItsSteam() {
        var door = ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER);
        door.json().addProperty("reboiler_duty", 5e6);
        door.json().addProperty("reboiler_drop", .5e5);
        var run = new Sheet().unit("T", "column", door).feed("feed", "T", "feed", crude(BASE.feedTemperatureKelvin(), 2.72e5)).feed("steam", "T", "reboiler_a", water(600, 100e5, 80)).product("condensate", "T", "reboiler_b")
                .product("overhead", "T", "overhead").product("distillate", "T", "distillate").product("water", "T", "water").product("bottoms", "T", "bottoms").run();
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertEquals(-5e6, run.streams().get("condensate").enthalpy() - run.streams().get("steam").enthalpy(), .1, "the steam gives what the column takes");
        assertEquals(99.5e5, run.streams().get("condensate").pressure(), 1e-6);
        assertEquals(run.reports().get("T").readings().get("energy_gap"), run.heatGap(), 1, "a wired kettle books no duty");
        assertTrue(run.reports().values().stream().flatMap(report -> report.notes().stream()).anyMatch(note -> note.key().equals("column.reboiler_cold")), "80 mol/s of steam cannot give 5 MW above the bottoms");
    }

    @Test void waterInAHotFeedJoinsAsSteam() {
        var wet = new LinkedHashMap<>(crude(BASE.feedTemperatureKelvin(), FEED_TRAY).amounts());
        wet.put("Water", 50.);
        var run = new Sheet().unit("T", "column", ColumnDoor.from(BASE, ColumnComponents.Condenser.AIR_COOLER)).feed("feed", "T", "feed", new Flowsheet.Feed(BASE.feedTemperatureKelvin(), FEED_TRAY, wet, List.of()))
                .product("overhead", "T", "overhead").product("distillate", "T", "distillate").product("water", "T", "water").product("bottoms", "T", "bottoms").run();
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        assertTrue(run.streams().get("water").total() > alone(FEED_TRAY).run().streams().get("water").total(), "the steam condenses overhead with the rest");
    }
}

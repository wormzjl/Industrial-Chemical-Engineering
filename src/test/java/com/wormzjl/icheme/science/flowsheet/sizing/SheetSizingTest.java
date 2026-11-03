package com.wormzjl.icheme.science.flowsheet.sizing;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.NozzleBore;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.ExchangerRating;
import com.wormzjl.icheme.science.fluid.network.AirCooled;
import com.wormzjl.icheme.science.fluid.network.ExchangerRelay;
import com.wormzjl.icheme.science.fluid.network.HeatCurve;
import com.wormzjl.icheme.science.fluid.network.HeatControl;
import com.wormzjl.icheme.science.fluid.network.HeatSide;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S2's gate on real runs (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.5 and 3.11): the door's surface against
 * the world's kernels at one duty, the loads a stream gives, and the one entry point's order on small sheets. S4a's (section 3.11
 * item 3, D19): a sized unit's drop in the run, facts no passage reads, a type key's fact, and Size all's one edit.
 */
final class SheetSizingTest {
    private static final String PACKAGE = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, PACKAGE, () -> FluidThermodynamics.forNetwork(CATALOG, PACKAGE));
    private static final FlowThermo THERMO = new FlowThermo(MODEL, CATALOG, PACKAGE, () -> {
    });
    private static final List<String> AXIS = MODEL.components();
    private static final int WATER = AXIS.size() - 1, NITROGEN = AXIS.indexOf(FluidMaterialCatalog.NITROGEN);
    private static final double[] DRY_CRUDE = dryCrude();

    private static double[] dryCrude() {
        var crude = Arrays.copyOf(V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE).crudeFeed("icheme:tia_juana_light_methane").moleFractions(), AXIS.size());
        crude[WATER] = 0;
        return crude;
    }

    private static double[] scaled(double[] amounts, double total) {
        double sum = Arrays.stream(amounts).sum();
        return Arrays.stream(amounts).map(amount -> amount * total / sum).toArray();
    }

    private static double[] pure(int component, double flow) {
        double[] amounts = new double[AXIS.size()];
        amounts[component] = flow;
        return amounts;
    }

    private static FlowStream at(double temperature, double pressure, double[] amounts) {
        return THERMO.atTemperature(temperature, pressure, amounts, SolidInventory.EMPTY);
    }

    private static <T> T on(Supplier<T> test) {
        return MaterialRuntime.with(CATALOG, PACKAGE, test::get);
    }

    private static double[] wall(double temperature) {
        double[] wall = new double[HeatSides.EXCHANGER_CELLS];
        Arrays.fill(wall, temperature);
        return wall;
    }

    /** The door's surface for a duty, set on the world's own wall: that surface passes that duty, to the cells' own error. */
    @Test void theDoorsSurfaceIsWhatTheWorldPassesAtThatDuty() {
        on(() -> {
            var hot = at(450, 8e5, scaled(DRY_CRUDE, 300));
            var cold = at(300, 8e5, pure(WATER, 2000));
            var hotSide = ExchangerRating.side(THERMO, hot, 300, 450);
            var coldSide = ExchangerRating.side(THERMO, cold, 300, 450);
            double duty = hot.enthalpy() - at(360, 8e5, scaled(DRY_CRUDE, 300)).enthalpy();
            var rated = ExchangerRating.rate(hotSide, coldSide, duty);
            assertTrue(Double.isFinite(rated.area()) && rated.area() > 0);
            var a = new ExchangerRelay.Stream(hotSide.curve(), hotSide.inlet(), hotSide.massFlow(), new HeatSide(rated.area(), HeatCurve.Films.DEFAULT, wall(350), 0, 0, 1, 1), true);
            var b = new ExchangerRelay.Stream(coldSide.curve(), coldSide.inlet(), coldSide.massFlow(), new HeatSide(rated.area(), HeatCurve.Films.DEFAULT, wall(350), 0, 0, 1, 1), false);
            var wall = ExchangerRelay.wall(a, b, null);
            assertTrue(wall.converged());
            double passed = hotSide.massFlow() * (hotSide.inlet() - ExchangerRelay.outlet(a, wall.temperatures()));
            assertEquals(duty, passed, .01 * duty, "the plan's 0.9 % of eight cells");
            return null;
        });
    }

    /** An air cooler's surface against the world's own march: exact, for both stand on one curve against a flat air. */
    @Test void theCoolersSurfaceIsWhatTheWorldsMarchPasses() {
        on(() -> {
            var in = at(470, 8e5, scaled(DRY_CRUDE, 300));
            var out = at(345, 8e5, scaled(DRY_CRUDE, 300));
            var load = AirCoolerSizing.Load.of(in, out, DesignBasis.DEFAULT, THERMO, new MixtureViscosity.Workspace());
            var side = ExchangerRating.side(THERMO, in, Math.min(out.temperature(), HeatSides.AIR_TEMPERATURE), in.temperature());
            var fans = side.curve().withFilms(HeatSides.airFilms(1));
            var world = new AirCooled(load.needed(), HeatSides.AIR, 1, null, HeatControl.Limit.NONE);
            double passed = -world.heat(fans, side.inlet(), 1.1 * side.massFlow()), duty = 1.1 * (in.enthalpy() - out.enthalpy());
            assertEquals(duty, passed, .001 * duty);
            return null;
        });
    }

    private static ExchangerSizing.Load load(boolean kettle, FlowStream tubeIn, FlowStream tubeOut, FlowStream shellIn, FlowStream shellOut) {
        return ExchangerSizing.Load.of(kettle, tubeIn, tubeOut, shellIn, shellOut, DesignBasis.DEFAULT, THERMO, new MixtureViscosity.Workspace());
    }

    @Test void eachSideReadsTheDropOfWhatItDoes() {
        on(() -> {
            var saturated = 413.;
            double pressure = MODEL.saturationPressure(saturated);
            var steamIn = at(saturated + 10, pressure, pure(WATER, 500));
            var steamOut = at(saturated - 10, pressure, pure(WATER, 500));
            var cold = at(300, 8e5, scaled(DRY_CRUDE, 300));
            var warm = at(350, 8e5, scaled(DRY_CRUDE, 300));
            var condenser = load(false, cold, warm, steamIn, steamOut);
            assertSame(Limit.EXCHANGER_DROP_LIQUID, condenser.tubeDrop(), "the crude warms");
            assertSame(Limit.EXCHANGER_DROP_GAS, condenser.shellDrop(), "the steam condenses");
            var hot = at(600, 40e5, pure(WATER, 2000));
            var cooler = at(560, 40e5, pure(WATER, 2000));
            var liquid = at(saturated - 20, pressure, pure(WATER, 100));
            var boiled = at(saturated + 5, pressure, pure(WATER, 100));
            var boiler = load(false, liquid, boiled, hot, cooler);
            assertSame(Limit.EXCHANGER_DROP_BOILING, boiler.tubeDrop(), "the water boils");
            var gas = at(300, 5e5, pure(NITROGEN, 100));
            var warmed = at(400, 5e5, pure(NITROGEN, 100));
            assertSame(Limit.EXCHANGER_DROP_GAS, load(false, gas, warmed, hot, cooler).tubeDrop(), "a gas that stays one");
            return null;
        });
    }

    @Test void heatThatCannotCrossHasNoSurface() {
        on(() -> {
            var cold = at(300, 8e5, pure(WATER, 100));
            var warm = at(340, 8e5, pure(WATER, 100));
            var heater = at(330, 8e5, scaled(DRY_CRUDE, 300));
            var cooled = at(320, 8e5, scaled(DRY_CRUDE, 300));
            var cross = load(false, cold, warm, heater, cooled);
            assertEquals(Double.POSITIVE_INFINITY, cross.needed());
            assertEquals(Lamp.RED, ExchangerSizing.auto(cross).rating().lamp());
            assertFalse(ExchangerSizing.auto(cross).rating().reading(Limit.EXCHANGER_HOT_END).met());
            var below = at(290, 8e5, scaled(DRY_CRUDE, 300));
            var colder = at(280, 8e5, scaled(DRY_CRUDE, 300));
            assertEquals(Double.POSITIVE_INFINITY, load(false, cold, warm, below, colder).needed(), "a hot inlet under the cold one");
            return null;
        });
    }

    @Test void coolingWaterReadsItsOutletOnItsColdSide() {
        on(() -> {
            var hot = at(400, 8e5, scaled(DRY_CRUDE, 300));
            var cooler = at(350, 8e5, scaled(DRY_CRUDE, 300));
            var water = at(303, 4e5, pure(WATER, 5000));
            var warm = at(320, 4e5, pure(WATER, 5000));
            assertEquals(320 - 273.15, load(false, water, warm, hot, cooler).waterOut(), 1e-9);
            assertNull(load(false, cooler, hot, at(420, 40e5, pure(WATER, 2000)), at(400, 40e5, pure(WATER, 2000))).waterOut());
            return null;
        });
    }

    @Test void aGassCapacityGivesItsAdiabaticExponent() {
        on(() -> {
            var in = at(300, 2e5, pure(NITROGEN, 100));
            var out = at(300, 6e5, pure(NITROGEN, 100));
            var load = CompressorSizing.Load.of(in, out, DesignBasis.DEFAULT, THERMO, new MixtureViscosity.Workspace());
            assertEquals(2. / 7, load.exponent(), .005, "a diatomic gas");
            assertEquals(3, load.ratio(), 1e-12);
            assertEquals(0, load.liquid(), 1e-9);
            assertEquals(1.1 * in.state().volume() * 3600, load.flow(), 1e-6);
            assertEquals(CompressorSizing.rate(load, CompressorSizing.auto(load).size()), CompressorSizing.auto(load).rating());
            return null;
        });
    }

    // ---- The entry point ----

    /** A sheet by hand, as the file holds it. */
    private static final class Sheet {
        private final List<Flowsheet.Unit> units = new ArrayList<>();
        private final List<Flowsheet.Stream> streams = new ArrayList<>();

        Sheet unit(String id, String type, String settings) {
            units.add(new Flowsheet.Unit(id, type, id, 0, 0, new Params(JsonParser.parseString(settings).getAsJsonObject())));
            return this;
        }

        Sheet feed(String id, String unit, String port, double temperature, double pressure, double[] amounts) {
            var ids = new LinkedHashMap<String, Double>();
            for (int i = 0; i < amounts.length; i++) if (amounts[i] > 0) ids.put(AXIS.get(i), amounts[i]);
            streams.add(new Flowsheet.Stream(id, id, null, new Flowsheet.End(unit, port), new Flowsheet.Feed(temperature, pressure, ids, List.of())));
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

        Flowsheet flowsheet() {
            return new Flowsheet(new Flowsheet.Basis(PACKAGE, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
        }

        SheetSizing.Sized sized(SheetSizing.Entries entries) {
            var sheet = flowsheet();
            var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
            });
            assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
            return on(() -> SheetSizing.pass(sheet, run, THERMO, entries));
        }

        SheetSizing.Sized sized() {
            return sized(SheetSizing.Entries.DEFAULT);
        }
    }

    private static double[] wetCrude(double total) {
        double[] amounts = scaled(DRY_CRUDE, total);
        amounts[WATER] = .3 * total;
        amounts[NITROGEN] = .1 * total;
        return amounts;
    }

    /** A wet crude split in a drum: its oil over a pump, its gas through a compressor, its water to a tank. */
    private static Sheet separator() {
        return new Sheet().unit("D", "drum", "{\"pressure_mode\":\"inlet\"}").unit("P", "pump", "{\"target\":\"rise\",\"rise\":3e5}").unit("C", "compressor", "{\"target\":\"rise\",\"rise\":6e5}")
                .unit("T", "storage_tank", "{}").feed("feed", "D", "in", 340, 3e5, wetCrude(40)).line("oil", "D", "oil", "P", "in").product("oil out", "P", "out").line("gas", "D", "gas", "C", "in")
                .product("gas out", "C", "out").line("water", "D", "water", "T", "in");
    }

    /** What a run that failed, stopped or did not close leaves is no steady state: every item is red on that alone, with no size. */
    @Test void aRunThatIsNotSteadySizesNothing() {
        var sheet = separator().flowsheet();
        for (var run : List.of(FlowsheetRun.none(), FlowsheetRun.refused(new Note(Note.Severity.ERROR, "D", "test", List.of())))) {
            var sized = on(() -> SheetSizing.pass(sheet, run, THERMO, SheetSizing.Entries.DEFAULT));
            assertEquals(sheet.streams().size(), sized.lines().size());
            assertEquals(sheet.units().size(), sized.units().size());
            var items = new ArrayList<Rating>();
            sized.lines().values().forEach(line -> {
                assertNull(line.size());
                items.add(line.rating());
            });
            sized.units().values().forEach(unit -> {
                assertNull(unit.size());
                items.add(unit.rating());
            });
            for (var rating : items) {
                assertEquals(Lamp.RED, rating.lamp());
                assertEquals(List.of(Limit.RUN_STEADY), rating.readings().stream().map(Reading::limit).toList());
            }
        }
    }

    @Test void aSecondLiquidTakesABootedDrum() {
        var size = (DrumSizing.Size) separator().sized().units().get("D").size();
        assertEquals(UnitType.LYING_DRUM, size.type());
        assertTrue(size.boot());
    }

    /** A 2 × 6 m drum's oil leaves beside its saddle, where its flange has room for less than a pipe's largest. */
    @Test void aSizedDrumsNozzleBoundsItsLine() {
        var size = new DrumSizing.Size(UnitType.LYING_DRUM, 2, 6, true);
        var sized = separator().sized(new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("D", new SheetSizing.UnitEntry(size, null, null, null, false))));
        var shape = size.shape();
        double bore = PipeSizes.MAX_BORE;
        for (int k = 0; k < shape.nozzles().size(); k++) if (shape.nozzles().get(k).role() == EquipmentShape.Role.LIQUID_OUTLET) bore = NozzleBore.largest(shape, k);
        assertEquals(bore, sized.lines().get("oil").line().nozzle(), 0, "the smaller of its two ends");
        assertTrue(bore < PipeSizes.MAX_BORE, "this boot's outlet stands beside its saddle");
        assertEquals(PipeSizes.MAX_BORE, sized.lines().get("water").line().nozzle(), 0);
        assertEquals(PipeSizes.MAX_BORE, sized.lines().get("feed").line().nozzle(), 0);
    }

    @Test void aSuctionLinesDropFeedsItsPumpsNpsh() {
        var sized = separator().sized();
        var auto = sized.units().get("P").rating().reading(Limit.PUMP_NPSH);
        assertNotNull(auto, "a drum's outlet is no column's: it stands at grade until a height is given");
        var narrow = separator().sized(new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of("oil", new SheetSizing.LineEntry(null, null, new LineSizing.Size(.05, 1))), Map.of())).units().get("P");
        assertTrue(narrow.rating().reading(Limit.PUMP_NPSH).value() < auto.value(), "a narrower line leaves less");
    }

    @Test void aGivenHeightRaisesItByItsOwnHead() {
        var low = separator().sized().units().get("P").rating().reading(Limit.PUMP_NPSH).value();
        var high = separator().sized(new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("P", new SheetSizing.UnitEntry(null, null, 7., null, false)))).units().get("P").rating().reading(Limit.PUMP_NPSH).value();
        assertEquals(7, high - low, 1e-9);
    }

    @Test void aPlayersSizeIsRatedAndAutoFillsTheRest() {
        var fixed = new PumpSizing.Size(.6);
        var sized = separator().sized(new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("P", new SheetSizing.UnitEntry(fixed, null, null, null, false))));
        assertEquals(fixed, sized.units().get("P").size());
        assertEquals(Lamp.YELLOW, sized.units().get("P").rating().lamp(), "far under its rated flow");
        assertNotNull(sized.units().get("D").size());
    }

    @Test void aTypeKeyStandsOverTheProposal() {
        var kept = separator().sized(new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("T", new SheetSizing.UnitEntry(null, UnitType.SPHERE, null, null, false))));
        assertEquals(UnitType.SPHERE, ((TankSizing.Size) kept.units().get("T").size()).type());
        assertEquals(UnitType.FLOATING_ROOF, ((TankSizing.Size) separator().sized().units().get("T").size()).type(), "water at 340 K under its roof");
    }

    @Test void aCompressorAndATankStandOnTheirStreams() {
        var sized = separator().sized();
        assertEquals(UnitType.PISTON, ((CompressorSizing.Size) sized.units().get("C").size()).type());
        assertNotNull(sized.units().get("C").rating().reading(Limit.COMPRESSOR_DISPLACEMENT));
        assertNotNull(sized.units().get("T").rating().reading(Limit.TANK_HOLD));
    }

    @Test void aHeatersCoilsSetApartShareOneDuty() {
        var series = new Sheet().unit("H", "fired_heater", "{\"coils\":\"series\",\"target\":\"duty\",\"duty\":3e7}").feed("oil", "H", "in", 400, 20e5, scaled(DRY_CRUDE, 300)).product("out", "H", "out").sized();
        assertEquals(33, series.units().get("H").rating().reading(Limit.HEATER_DUTY).low(), 1e-6, "110 % of 30 MW");
        var apart = new Sheet().unit("H", "fired_heater", "{\"coils\":\"apart\",\"target\":\"duty\",\"duty\":2e7,\"convection_target\":\"duty\",\"convection_duty\":1e7}")
                .feed("oil", "H", "radiant_in", 400, 20e5, scaled(DRY_CRUDE, 300)).feed("steam", "H", "convection_in", 450, 5e5, pure(WATER, 100)).product("hot", "H", "radiant_out").product("superheat", "H", "convection_out").sized();
        assertEquals(33, apart.units().get("H").rating().reading(Limit.HEATER_DUTY).low(), 1e-6);
    }

    @Test void anExchangerAndAKettleStandOnTheirStreams() {
        var sized = new Sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":360}").unit("K", "kettle", "{\"target\":\"duty\",\"side\":\"shell\",\"duty\":2e6}")
                .feed("hot", "E", "shell_in", 420, 8e5, scaled(DRY_CRUDE, 300)).feed("water", "E", "tube_in", 303, 4e5, pure(WATER, 10000)).product("hot out", "E", "shell_out").product("water out", "E", "tube_out")
                .feed("steam", "K", "tube_in", 470, 15e5, pure(WATER, 150)).feed("boil", "K", "feed", 380, 2e5, pure(WATER, 80)).product("cond", "K", "tube_out").product("vapour", "K", "gas").product("rest", "K", "liquid").sized();
        var exchanger = sized.units().get("E");
        assertEquals(Lamp.GREEN, exchanger.rating().lamp(), () -> exchanger.rating().toString());
        assertNotNull(exchanger.rating().reading(Limit.EXCHANGER_WATER_OUT));
        var kettle = sized.units().get("K");
        assertTrue(((ExchangerSizing.Size) kettle.size()).kettle());
        assertNotNull(kettle.rating().reading(Limit.KETTLE_FLUX));
    }

    @Test void aFreeSideLeavesNoSurfaceToRead() {
        var sized = new Sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":360}").feed("hot", "E", "shell_in", 420, 8e5, scaled(DRY_CRUDE, 300)).product("out", "E", "shell_out").sized();
        assertNull(sized.units().get("E").size());
        assertEquals(Lamp.NONE, sized.units().get("E").rating().lamp(), "not green: its rows have no input");
    }

    @Test void anAirCoolerStandsOnItsStreamsAndAMixerHasNoSize() {
        var sized = new Sheet().unit("A", "air_cooler", "{\"target\":\"outlet\",\"temperature\":340}").unit("M", "mixer", "{}").feed("one", "M", "in/1", 420, 8e5, scaled(DRY_CRUDE, 150)).feed("two", "M", "in/2", 420, 8e5, scaled(DRY_CRUDE, 150))
                .line("oil", "M", "out", "A", "in").product("out", "A", "out").sized();
        assertNotNull(sized.units().get("A").size());
        assertNull(sized.units().get("M").size());
        assertEquals(Lamp.NONE, sized.units().get("M").rating().lamp());
    }

    // ---- S4a: the run on sized drops (D19), the facts, Size all ----

    private static FlowsheetRun run(Flowsheet sheet) {
        var run = FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, run.status(), () -> "log " + run.log());
        return run;
    }

    /** Crude cooled on cooling water, the water in the tubes. */
    private static Sheet cooler(double water) {
        return new Sheet().unit("E", "exchanger", "{\"target\":\"outlet\",\"side\":\"shell\",\"temperature\":360}").feed("hot", "E", "shell_in", 420, 8e5, scaled(DRY_CRUDE, 300))
                .feed("water", "E", "tube_in", 303, 4e5, pure(WATER, water)).product("hot out", "E", "shell_out").product("water out", "E", "tube_out");
    }

    /** Once a unit has a size, the run reads its law at the run's flow: twice the water loses near four times as much, turbulent. */
    @Test void aSizedDropFollowsTheRunsFlow() {
        var size = new ExchangerSizing.Size(false, 1, 1, 6);
        var facts = SizingFacts.NONE.with("E", SizingFacts.UnitFacts.AUTO.with(size));
        double[] drops = new double[2];
        for (int k = 0; k < 2; k++) {
            var run = run(cooler(10000 * (k + 1)).flowsheet().with(facts));
            var in = run.streams().get("water");
            drops[k] = run.reports().get("E/tube").readings().get("tube_drop");
            assertEquals(on(() -> SizedPassages.tube(size, in, 1, THERMO)), drops[k], 1e-9, "the tubes' own law at the run's flow");
            assertEquals(in.pressure() - drops[k], run.streams().get("water out").pressure(), 1e-6);
        }
        double ratio = drops[1] / drops[0];
        assertTrue(ratio > Math.pow(2, 1.7) && ratio < 4, () -> "twice the flow: " + ratio);
    }

    /** Facts no passage reads move no number: a pump's, a drum's, a type, a line's, the basis. */
    @Test void anUnsizedSheetsRunMovesNoNumber() {
        var plain = separator().flowsheet();
        var facts = SizingFacts.NONE.with("P", SizingFacts.UnitFacts.AUTO.with(new PumpSizing.Size(.3)).with(SizingFacts.Mode.FIXED))
                .with("D", SizingFacts.UnitFacts.AUTO.with(new DrumSizing.Size(UnitType.LYING_DRUM, 2, 6, true))).with("T", SizingFacts.UnitFacts.AUTO.typed(UnitType.SPHERE))
                .with("oil", SizingFacts.LineFacts.AUTO.with(new LineSizing.Size(.1, 1)).lasting(200.)).with(new DesignBasis(1.2, 80, 50));
        var before = run(plain);
        var after = run(plain.with(facts));
        before.streams().forEach((id, stream) -> {
            var other = after.streams().get(id);
            assertEquals(stream.pressure(), other.pressure(), 0, id);
            assertEquals(stream.enthalpy(), other.enthalpy(), 0, id);
            assertArrayEquals(stream.amounts(), other.amounts(), id);
        });
    }

    /** A type key's choice is a fact: Size all proposes in that type, and the rating reads the size it holds. */
    @Test void aTypeKeyIsAFactTheRatingReads() {
        var sheet = separator().flowsheet().with(SizingFacts.NONE.with("T", SizingFacts.UnitFacts.AUTO.typed(UnitType.SPHERE)));
        var run = run(sheet);
        var all = on(() -> SheetSizing.sizeAll(sheet, run, THERMO));
        var held = all.sheet().sizing().unit("T");
        assertEquals(UnitType.SPHERE, held.type());
        assertEquals(UnitType.SPHERE, ((TankSizing.Size) held.size()).type());
        assertEquals(held.size(), on(() -> SheetSizing.rate(all.sheet(), run, THERMO)).units().get("T").size());
    }

    /** Size all's one edit: Auto items take the proposal, Fixed items keep theirs, a deleted item's facts go, the sheet it was given stays. */
    @Test void sizeAllTakesAutoAndKeepsFixed() {
        var fixed = new PumpSizing.Size(.6);
        var stale = new DrumSizing.Size(UnitType.LYING_DRUM, 9, 30, true);
        var facts = SizingFacts.NONE.with("P", SizingFacts.UnitFacts.AUTO.with(fixed).with(SizingFacts.Mode.FIXED)).with("D", SizingFacts.UnitFacts.AUTO.with(stale))
                .with("gone", SizingFacts.UnitFacts.AUTO.with(new PumpSizing.Size(.2)));
        var sheet = separator().flowsheet().with(facts);
        var run = run(sheet);
        var all = on(() -> SheetSizing.sizeAll(sheet, run, THERMO));
        var applied = all.sheet().sizing();
        assertEquals(fixed, applied.unit("P").size());
        assertEquals(SizingFacts.Mode.FIXED, applied.unit("P").mode());
        assertEquals(all.sized().units().get("D").size(), applied.unit("D").size());
        assertNotEquals(stale, applied.unit("D").size(), "Auto proposes again");
        assertEquals(all.sized().lines().get("oil").size(), applied.line("oil").size());
        assertEquals(SizingFacts.UnitFacts.AUTO, applied.unit("gone"));
        assertEquals(facts, sheet.sizing(), "the sheet it was given stays");
        assertSame(run, all.run(), "no size here moves the run");
    }
}

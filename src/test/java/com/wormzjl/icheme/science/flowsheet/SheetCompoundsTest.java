package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * A sheet runs on its own compound list (batch 2026-11-04-refinery-compounds, W3; owner 2026-11-04): the network package's compounds its
 * feeds hold, on the package's view of them. Nothing reacts on a sheet, so every state a run reaches holds only those compounds, and the
 * view gives them the package's numbers bit for bit (V1, {@code RestrictedPackagePhysicsTest}); the run's streams come back on the
 * network's axis. An edit to another compound's records moves nothing the sheet stands on.
 */
final class SheetCompoundsTest {
    private static final MaterialCatalog CATALOG = com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics MODEL = MaterialRuntime.with(CATALOG, NETWORK, () -> FluidThermodynamics.forNetwork(CATALOG, NETWORK));
    private static final List<String> AXIS = MODEL.components();
    /** The default column preset's crude: the network's 19 crude compounds, no nitrogen. */
    private static final Map<String, Double> CRUDE = crude();

    private static Map<String, Double> crude() {
        var input = CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG);
        var ids = input.componentBasis().componentIds();
        double[] flows = input.feedComponentMolarFlowsMolPerSecond();
        var amounts = new LinkedHashMap<String, Double>();
        for (int i = 0; i < flows.length; i++) if (flows[i] > 0) amounts.put(ids.get(i), flows[i]);
        return amounts;
    }

    private static Flowsheet.Feed feed(Map<String, Double> amounts, double temperature, double pressure) {
        return new Flowsheet.Feed(temperature, pressure, amounts, List.of());
    }

    /** A crude feed let down through a valve into a drum: two phases, a PH search on each step. */
    private static Flowsheet sheet(String drawn, Map<String, Double> amounts) {
        var units = List.of(new Flowsheet.Unit("V", "valve", "V", 0, 0, new Params(JsonParser.parseString("{\"target\":\"drop\",\"drop\":3e5}").getAsJsonObject())),
                new Flowsheet.Unit("D", "drum", "D", 0, 0, new Params(JsonParser.parseString("{}").getAsJsonObject())));
        var streams = List.of(new Flowsheet.Stream("F", "F", null, new Flowsheet.End("V", "a"), feed(amounts, 560, 5e5)),
                new Flowsheet.Stream("L", "L", new Flowsheet.End("V", "b"), new Flowsheet.End("D", "in"), null),
                new Flowsheet.Stream("G", "G", new Flowsheet.End("D", "gas"), null, null),
                new Flowsheet.Stream("O", "O", new Flowsheet.End("D", "oil"), null, null),
                new Flowsheet.Stream("W", "W", new Flowsheet.End("D", "water"), null, null));
        return new Flowsheet(new Flowsheet.Basis(drawn, "test", AXIS), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    private static Map<String, Double> with(Map<String, Double> amounts, String id, double amount) {
        var out = new LinkedHashMap<>(amounts);
        out.put(id, amount);
        return out;
    }

    @Test void aSheetsListIsWhatItsFeedsHoldInThePackagesOrder() {
        var crude = SheetCompounds.of(sheet(NETWORK, CRUDE), CATALOG);
        assertEquals(19, crude.size(), crude::toString);
        assertEquals(CATALOG.requirePackage(NETWORK).components().stream().filter(CRUDE::containsKey).toList(), crude, "the package's order");
        assertEquals(List.of("Nitrogen"), SheetCompounds.of(sheet(NETWORK, Map.of("Water", 5.)), CATALOG), "water is on every axis; nitrogen holds the place");
        var grown = SheetCompounds.of(sheet(NETWORK, with(CRUDE, "Nitrogen", .1)), CATALOG);
        assertEquals(CATALOG.requirePackage(NETWORK).components().stream().filter(c -> CRUDE.containsKey(c) || c.equals("Nitrogen")).toList(), grown, "a feed that gains a compound grows the list");
        assertSame(CATALOG, CATALOG.restrict(NETWORK, CATALOG.requirePackage(NETWORK).components()), "the whole package is its own view");
        assertEquals(crude, SheetCompounds.of(sheet(NETWORK, with(CRUDE, "Nitrogen", 0.)), CATALOG), "a zero amount brings nothing");
        assertEquals(crude.subList(1, crude.size()), SheetCompounds.of(sheet(NETWORK, with(CRUDE, crude.getFirst(), 0.)), CATALOG), "and a compound no feed holds goes");
    }

    /**
     * The run on the view of the crude's 19 compounds and the solve on the whole package give the same bits: amounts, temperatures,
     * pressures, enthalpies, masses and each state's phases, on the network's axis.
     */
    @Test void aRunOnTheViewIsTheWholePackagesRunBitForBit() {
        var sheet = sheet(NETWORK, CRUDE);
        var viewed = FlowsheetRuns.run(sheet, CATALOG, () -> {
        });
        var thermo = MaterialRuntime.with(CATALOG, NETWORK, () -> new FlowThermo(MODEL, CATALOG, NETWORK, () -> {
        }));
        var whole = MaterialRuntime.with(CATALOG, NETWORK, () -> FlowsheetRuns.solve(FlowsheetCompiler.compile(sheet, thermo, null), AXIS, thermo, sheet.settings(), () -> {
        }));
        assertEquals(FlowsheetRun.Status.STEADY, viewed.status(), () -> "log " + viewed.log());
        assertEquals(whole.streams().keySet(), viewed.streams().keySet());
        assertTrue(whole.streams().get("G").total() > 0 && whole.streams().get("O").total() > 0, "two phases");
        for (var id : whole.streams().keySet()) {
            var a = whole.streams().get(id);
            var b = viewed.streams().get(id);
            assertEquals(AXIS.size(), b.amountsView().length, id);
            for (int i = 0; i < AXIS.size(); i++) bits(a.amountsView()[i], b.amountsView()[i], id + " " + AXIS.get(i));
            bits(a.temperature(), b.temperature(), id + " T");
            bits(a.pressure(), b.pressure(), id + " P");
            bits(a.enthalpy(), b.enthalpy(), id + " H");
            bits(a.mass(), b.mass(), id + " mass");
            assertEquals(a.state() == null, b.state() == null, id);
            if (a.state() == null) continue;
            bits(a.state().volume(), b.state().volume(), id + " volume");
            bits(a.state().enthalpy(), b.state().enthalpy(), id + " state H");
            bits(TestPhases.waterLiquid(a.state()), TestPhases.waterLiquid(b.state()), id + " free water");
            for (int i = 0; i + 1 < AXIS.size(); i++) {
                bits(TestPhases.liquid(a.state())[i], TestPhases.liquid(b.state())[i], id + " liquid " + AXIS.get(i));
                bits(TestPhases.vapor(a.state())[i], TestPhases.vapor(b.state())[i], id + " vapour " + AXIS.get(i));
            }
        }
        assertEquals(whole.reports().get("D").readings(), viewed.reports().get("D").readings());
    }

    private static void bits(double expected, double actual, String what) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), () -> what + ": " + expected + " on the package, " + actual + " on the view");
    }

    /** The last run starts the next where its streams fit the view; one that holds a compound the sheet no longer feeds starts nothing. */
    @Test void aLastRunOnTheNetworksAxisStartsTheNext() {
        var sheet = sheet(NETWORK, CRUDE);
        var first = FlowsheetRuns.run(sheet, CATALOG, () -> {
        });
        var again = FlowsheetRuns.run(sheet, CATALOG, () -> {
        }, first);
        assertEquals(FlowsheetRun.Status.STEADY, again.status(), () -> "log " + again.log());
        double[] amounts = first.streams().get("L").amounts();
        amounts[AXIS.indexOf("Nitrogen")] = 1;
        var other = MaterialRuntime.with(CATALOG, NETWORK, () -> new FlowThermo(MODEL, CATALOG, NETWORK, () -> {
        }).atTemperature(400, 2e5, amounts, SolidInventory.EMPTY));
        var alone = FlowsheetEngine.alone(sheet, "D", CATALOG, () -> {
        }, Map.of("L", other), FlowsheetSolver.Progress.NONE);
        assertNull(alone.streams().get("G"), "the drum has no inlet it can hold, so it does not run");
        var fits = FlowsheetEngine.alone(sheet, "D", CATALOG, () -> {
        }, Map.of("L", first.streams().get("L")), FlowsheetSolver.Progress.NONE);
        assertNotNull(fits.streams().get("G"));
        for (int i = 0; i < AXIS.size(); i++) bits(first.streams().get("G").amountsView()[i], fits.streams().get("G").amountsView()[i], "alone G " + AXIS.get(i));
    }

    /**
     * A sheet drawn on another package, or on one since removed, runs on the network's method and records by compound ids (owner
     * 2026-11-04): the same bits as the sheet drawn on the network. A feed's compound the network does not hold is parked: the run
     * names it and goes on (helium, which no package holds; CO2 was the example until it joined the network in O11 of batch
     * 2026-11-04-open-thermo-data).
     */
    @Test void aSheetDrawnOnAnotherPackageRunsOnTheNetworksRecords() {
        var light = new LinkedHashMap<String, Double>();
        light.put("Methane", 1.);
        light.put("Ethane", 1.);
        light.put("crude_pc05", 1.);
        var drawn = FlowsheetRuns.run(sheet(NETWORK, light), CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, drawn.status(), () -> "log " + drawn.log());
        for (var other : List.of("icheme:pilot_cryogenic", "icheme:tjl20_methane", "icheme:retired_package")) {
            var run = FlowsheetRuns.run(sheet(other, light), CATALOG, () -> {
            });
            assertEquals(drawn.status(), run.status(), other);
            assertEquals(drawn.log(), run.log(), other);
            for (var id : drawn.streams().keySet())
                for (int i = 0; i < AXIS.size(); i++) bits(drawn.streams().get(id).amountsView()[i], run.streams().get(id).amountsView()[i], other + " " + id + " " + AXIS.get(i));
        }
        var carbon = FlowsheetRuns.run(sheet("icheme:pilot_cryogenic", with(light, "Helium", 1.)), CATALOG, () -> {
        });
        assertEquals(FlowsheetRun.Status.STEADY, carbon.status(), () -> "log " + carbon.log());
        assertTrue(carbon.reports().values().stream().flatMap(report -> report.notes().stream())
                .anyMatch(note -> note.key().equals("basis.parked") && note.arguments().equals(List.of("Helium"))), () -> "reports " + carbon.reports());
        assertEquals(SheetCompounds.of(sheet(NETWORK, light), CATALOG), SheetCompounds.of(sheet("icheme:pilot_cryogenic", with(light, "Helium", 1.)), CATALOG));
    }

    /**
     * A compound added to the network package moves nothing a sheet that does not hold it stands on (option B, 2026-11-04): its token
     * and its columns' warm-start identity stay, while the whole package's identity moves. The new compound is a copy of propane's
     * records under another id.
     */
    @Test void aCompoundAddedToTheNetworkKeepsTheTokenOfASheetWithoutIt() {
        var resources = new HashMap<>(CATALOG.resources());
        String root = "data/icheme/materials/";
        var component = JsonParser.parseString(resources.get(root + "components/propane.json")).getAsJsonObject();
        component.addProperty("id", "Testane");
        component.addProperty("translation_key", "material.icheme.testane");
        component.addProperty("fallback", "Testane");
        component.addProperty("properties", "test:testane");
        resources.put(root + "components/testane.json", component.toString());
        var dataset = JsonParser.parseString(resources.get(root + "properties/tjl19_propane.json")).getAsJsonObject();
        dataset.addProperty("id", "test:testane");
        dataset.addProperty("component", "Testane");
        resources.put(root + "properties/testane.json", dataset.toString());
        var record = JsonParser.parseString(resources.get(root + "packages/tjl20_nitrogen.json")).getAsJsonObject();
        record.getAsJsonArray("components").add("Testane");
        record.addProperty("revision", record.get("revision").getAsString() + "-testane");
        resources.put(root + "packages/tjl20_nitrogen.json", record.toString());
        var grown = MaterialCatalog.parse(resources);
        assertTrue(grown.requirePackage(NETWORK).components().contains("Testane"));
        var crude = sheet(NETWORK, CRUDE);
        var compounds = SheetCompounds.of(crude, CATALOG);
        assertEquals(compounds, SheetCompounds.of(crude, grown));
        assertEquals(RevisionNotice.tokens(crude, CATALOG), RevisionNotice.tokens(crude, grown), "the sheet's results stand");
        assertEquals(CATALOG.restrict(NETWORK, compounds).requirePackage(NETWORK).scientificRevision(), grown.restrict(NETWORK, compounds).requirePackage(NETWORK).scientificRevision(),
                "the identity a column's warm start is checked on");
        assertNotEquals(CATALOG.requirePackage(NETWORK).scientificRevision(), grown.requirePackage(NETWORK).scientificRevision(), "the whole package holds it");
        assertNotEquals(CATALOG.fluidRevision(NETWORK), grown.fluidRevision(NETWORK));
        var testane = sheet(NETWORK, with(CRUDE, "Testane", .1));
        assertTrue(SheetCompounds.of(testane, grown).contains("Testane"));
        assertTrue(RevisionNotice.dropsResults(RevisionNotice.tokens(crude, grown), RevisionNotice.tokens(testane, grown)), "a sheet that feeds it stands on other records");
    }

    /** An edit to a compound the sheet does not hold leaves its token, so its results and its columns' warm starts stand; one it holds moves it. */
    @Test void anotherCompoundsEditKeepsTheSheetsToken() {
        var resources = new HashMap<>(CATALOG.resources());
        String key = "data/icheme/materials/properties/nitrogen.json";
        String nitrogen = resources.get(key);
        assertTrue(nitrogen.contains("\"fluid-nitrogen-coolprop-r4\""), "the fixture's revision");
        resources.put(key, nitrogen.replace("\"fluid-nitrogen-coolprop-r4\"", "\"fluid-nitrogen-coolprop-r5\""));
        var edited = MaterialCatalog.parse(resources);
        var crude = sheet(NETWORK, CRUDE);
        var nitrogenous = sheet(NETWORK, with(CRUDE, "Nitrogen", .1));
        assertEquals(RevisionNotice.tokens(crude, CATALOG), RevisionNotice.tokens(crude, edited));
        assertTrue(RevisionNotice.dropsResults(RevisionNotice.tokens(nitrogenous, CATALOG), RevisionNotice.tokens(nitrogenous, edited)));
        var compounds = SheetCompounds.of(crude, CATALOG);
        assertEquals(CATALOG.restrict(NETWORK, compounds).requirePackage(NETWORK).scientificRevision(), edited.restrict(NETWORK, compounds).requirePackage(NETWORK).scientificRevision(),
                "the identity a column's warm start is checked on");
        assertNotEquals(CATALOG.requirePackage(NETWORK).scientificRevision(), edited.requirePackage(NETWORK).scientificRevision(), "the whole package's moved");
    }
}

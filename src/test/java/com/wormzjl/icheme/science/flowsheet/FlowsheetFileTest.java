package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flowsheet file (batch 2026-11-02-process-simulator, plan section 3.9; AGENTS.md, "Saves and data formats"): every corpus image
 * under {@code compat/flowsheet/} reads and writes back all it holds; what this version no longer has stays in the sheet and out of
 * the run; a damaged file or a newer format is refused and left as it is.
 */
final class FlowsheetFileTest {
    private static final Path CORPUS = Path.of("src/test/resources/compat/flowsheet");
    private static final Path FOLDER = Path.of("build/compat/flowsheet-files");
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = FluidMaterialCatalog.NETWORK_PACKAGE;
    private static final List<String> AXIS = FluidThermodynamics.forNetwork(CATALOG, NETWORK).components();

    private static byte[] imageBytes() {
        try {
            return Files.readAllBytes(CORPUS.resolve("format-" + FlowsheetFile.FORMAT + ".json"));
        } catch (IOException missing) {
            throw new AssertionError("every format has its image", missing);
        }
    }

    private static JsonObject image() {
        return tree(imageBytes());
    }

    private static JsonObject tree(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] bytes(JsonObject json) {
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject row(JsonObject json, String rows, String id) {
        for (var row : json.getAsJsonArray(rows)) if (row.getAsJsonObject().get("id").getAsString().equals(id)) return row.getAsJsonObject();
        throw new AssertionError(id);
    }

    private static JsonObject unit(JsonObject json, String id) {
        return row(json, "units", id);
    }

    private static JsonObject stream(JsonObject json, String id) {
        return row(json, "streams", id);
    }

    private static JsonObject feed(JsonObject json) {
        return stream(json, "F-1").getAsJsonObject("feed");
    }

    private static FlowsheetRun run(Flowsheet sheet) {
        return FlowsheetEngine.run(sheet, CATALOG, () -> {
        });
    }

    private static boolean logged(FlowsheetRun run, String key) {
        return notes(run).anyMatch(note -> note.key().equals(key));
    }

    private static java.util.stream.Stream<Note> notes(FlowsheetRun run) {
        return java.util.stream.Stream.concat(run.log().stream(), run.reports().values().stream().flatMap(report -> report.notes().stream()));
    }

    private static int count(byte[] bytes, String word) {
        return new String(bytes, StandardCharsets.UTF_8).split(word, -1).length - 1;
    }

    /** The gate reads every image, so a later format's image joins it by being there. */
    @Test void everyImageWritesBackAllItHolds() throws IOException {
        var images = new ArrayList<Path>();
        try (var files = Files.list(CORPUS)) {
            files.sorted().forEach(images::add);
        }
        assertTrue(images.contains(CORPUS.resolve("format-" + FlowsheetFile.FORMAT + ".json")), "the current format has its image");
        for (var image : images) {
            byte[] bytes = Files.readAllBytes(image);
            byte[] written = FlowsheetFile.write(FlowsheetFile.read(bytes));
            assertEquals(tree(bytes), tree(written), image.toString());
            assertEquals(count(bytes, "\"later"), count(written, "\"later"), () -> "what a later version added stays: " + image);
        }
    }

    @Test void aSheetRoundTrips() {
        var sheet = FlowsheetFile.read(imageBytes());
        byte[] written = FlowsheetFile.write(sheet);
        assertEquals(sheet, FlowsheetFile.read(written));
        assertArrayEquals(written, FlowsheetFile.write(FlowsheetFile.read(written)), "a written sheet is its own fixed point");
    }

    @Test void aDrawnSheetRoundTrips() {
        var feed = new Flowsheet.Feed(300, 1e5, Map.of("Water", 1e-300), List.of());
        var units = List.of(new Flowsheet.Unit("V", "valve", "a \"quoted\" tag <é>", -4, 7, new Params(new JsonObject())));
        var streams = List.of(new Flowsheet.Stream("s", "s", null, new Flowsheet.End("V", "in"), feed));
        var sheet = new Flowsheet(new Flowsheet.Basis(NETWORK, "r1", AXIS), units, streams, new FlowsheetSolver.Settings(1e-5, .1, 7, -2, -.5));
        assertEquals(sheet, FlowsheetFile.read(FlowsheetFile.write(sheet)));
    }

    /** What the image's sheet is: a live front end, then a removed type, with the column behind it never reached. */
    @Test void aRemovedTypeStandsAndEverythingUpstreamSolves() {
        var sheet = FlowsheetFile.read(imageBytes());
        var run = run(sheet);
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertEquals(FlowsheetRun.Outcome.SOLVED, run.outcomes().get("V-1"));
        assertEquals(FlowsheetRun.Outcome.SOLVED, run.outcomes().get("D-1"));
        assertEquals(FlowsheetRun.Outcome.FAILED, run.outcomes().get("X-1"));
        assertEquals(FlowsheetRun.Outcome.NOT_SOLVED, run.outcomes().get("T-1"));
        assertTrue(logged(run, "unit.removed"), () -> "log " + run.log());
        assertEquals("retired_gizmo", sheet.units().get(2).type());
        assertEquals(.2, sheet.units().get(2).params().number("bore"));
        // PA 1's coolant is a feed of water alone, whose flow the column sets from its draw tray (COOLER_MODEL_PLAN.md section 8): it
        // waits for the column, which the removed type keeps from running.
        assertEquals(FlowsheetRun.Outcome.NOT_SOLVED, run.outcomes().get("T-1/pa 1 utility"), "a regulated heat side waits for its column");
        assertEquals(FlowsheetRun.Outcome.NOT_SOLVED, run.outcomes().get("T-1/pa 1"));
        assertTrue(run.streams().containsKey("O-1") && !run.streams().containsKey("T-5"));
    }

    @Test void aRemovedComponentIsParked() {
        var sheet = FlowsheetFile.read(imageBytes());
        var thermo = MaterialRuntime.with(CATALOG, NETWORK, () -> new FlowThermo(FluidThermodynamics.forNetwork(CATALOG, NETWORK), CATALOG, NETWORK, () -> {
        }));
        assertEquals(List.of("Retired_cut", "icheme:retired_powder"), FlowsheetCompiler.parked(sheet, thermo));
        var run = run(sheet);
        assertEquals(List.of("Retired_cut", "icheme:retired_powder"), notes(run).filter(note -> note.key().equals("basis.parked")).map(note -> note.arguments().getFirst()).toList(), "the run names them");
        var feed = run.streams().get("F-1");
        assertEquals(100, feed.total(), 1e-12, "out of the solve");
        assertEquals(.5, feed.solids().moments().mass(), 1e-12);
        var written = tree(FlowsheetFile.write(sheet));
        assertEquals(5, feed(written).getAsJsonObject("amounts").get("Retired_cut").getAsDouble(), "and in the file");
        assertEquals(.25, feed(written).getAsJsonArray("solids").get(1).getAsJsonObject().get("mass_flow").getAsDouble());
    }

    @Test void anAddedComponentStartsAtZero() {
        var feed = run(FlowsheetFile.read(imageBytes())).streams().get("F-1");
        assertEquals(0, feed.amounts()[AXIS.indexOf("Nitrogen")], "the image has no nitrogen in its feed");
        assertEquals(0, feed.amounts()[AXIS.indexOf("Propane")]);
    }

    @Test void aFeedOfParkedComponentsCarriesNothing() {
        var json = image();
        var amounts = new JsonObject();
        amounts.addProperty("Retired_cut", 5);
        feed(json).add("amounts", amounts);
        feed(json).remove("solids");
        var run = run(FlowsheetFile.read(bytes(json)));
        assertEquals(0, run.streams().get("F-1").total());
        assertEquals(FlowsheetRun.Outcome.SOLVED, run.outcomes().get("D-1"), "no flow is no failure");
    }

    /** A sheet drawn on a package gone from this version, given the world's: its amounts go by id. */
    @Test void aRemovedPackageAsksForAnother() {
        var json = image();
        json.getAsJsonObject("basis").addProperty("package", "icheme:retired_package");
        var sheet = FlowsheetFile.read(bytes(json));
        var refused = run(sheet);
        assertEquals(FlowsheetRun.Status.FAILED, refused.status());
        assertEquals("basis.package", refused.log().getFirst().key());
        assertEquals(List.of("icheme:retired_package", NETWORK), refused.log().getFirst().arguments());
        assertEquals(tree(imageBytes()).get("streams"), tree(FlowsheetFile.write(sheet)).get("streams"), "the sheet keeps all it holds");
        var given = new Flowsheet(new Flowsheet.Basis(NETWORK, sheet.basis().revision(), sheet.basis().components()), sheet.units(), sheet.streams(), sheet.settings());
        assertArrayEquals(run(FlowsheetFile.read(imageBytes())).streams().get("O-1").amounts(), run(given).streams().get("O-1").amounts());
    }

    @Test void aRemovedRowLeavesItsStreamsOpenAndMovesNoOther() {
        var json = image();
        unit(json, "T-1").getAsJsonObject("params").getAsJsonArray("pumparounds").remove(0);
        var sheet = FlowsheetFile.read(bytes(json));
        var run = run(sheet);
        assertFalse(run.outcomes().containsKey("T-1/pa 1"), "the row's heat side went with it");
        assertTrue(notes(run).anyMatch(note -> note.key().equals("stream.open") && note.arguments().equals(List.of("PA 1 RETURN"))), () -> "its return is an open pill: log " + run.log());
        // PA 2's water waits for the column, which the removed type keeps from running (section 8); its side and its wires stand.
        assertTrue(run.outcomes().containsKey("T-1/pa 2") && run.outcomes().containsKey("T-1/pa 2 utility"), "PA 2 keeps its wires");
        assertEquals(-1.5e7, com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(sheet.units().stream().filter(unit -> unit.id().equals("T-1")).findFirst().orElseThrow().params()).pumparounds().getFirst().duty(), 1, "and its duty");
        assertTrue(run.streams().containsKey("P-1"), "the row's coolant stands as a feed with nowhere to go");
        assertEquals("pa/1/in", sheet.streams().stream().filter(stream -> stream.id().equals("P-1")).findFirst().orElseThrow().to().port(), "the sheet keeps the end, so Undo finds it");
    }

    @Test void aRenamedTypeAndPortReadByTheirAliases() {
        var aliases = new UnitTypes.Aliases(Map.of("flash_drum", "drum"), Map.of("drum", Map.of("vapour", "gas")));
        var json = image();
        unit(json, "D-1").addProperty("type", "flash_drum");
        stream(json, "G-1").getAsJsonObject("from").addProperty("port", "vapour");
        byte[] written = FlowsheetFile.write(FlowsheetFile.read(bytes(json), aliases));
        assertEquals(image(), tree(written), "a sheet holds today's names");
    }

    private static void damaged(String where, Consumer<JsonObject> damage) {
        var json = image();
        damage.accept(json);
        var refusal = assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(bytes(json)), where);
        assertEquals("file.damaged", refusal.key(), where);
        assertEquals(List.of(where), refusal.arguments());
    }

    @Test void aDamagedFieldRefusesTheFileAndSaysWhere() {
        damaged("sheet.format", json -> json.remove("format"));
        damaged("sheet.format", json -> json.addProperty("format", "one"));
        damaged("sheet.format", json -> json.addProperty("format", 0));
        damaged("sheet.format", json -> json.addProperty("format", 1.5));
        damaged("sheet.basis", json -> json.remove("basis"));
        damaged("sheet.basis", json -> json.addProperty("basis", "none"));
        damaged("sheet.basis.package", json -> json.getAsJsonObject("basis").addProperty("package", ""));
        damaged("sheet.basis.components", json -> json.getAsJsonObject("basis").getAsJsonArray("components").add("Water"));
        damaged("sheet.units", json -> json.remove("units"));
        damaged("sheet.units", json -> json.addProperty("units", 1));
        damaged("sheet.units[0]", json -> json.getAsJsonArray("units").set(0, new JsonArray()));
        damaged("sheet.units[0].id", json -> unit(json, "V-1").remove("id"));
        damaged("sheet.units[1]", json -> unit(json, "D-1").addProperty("id", "V-1"));
        damaged("sheet.units[0].x", json -> unit(json, "V-1").addProperty("x", 1.5));
        damaged("sheet.units[0].y", json -> unit(json, "V-1").addProperty("y", "top"));
        damaged("sheet.units[0].params", json -> unit(json, "V-1").addProperty("params", "none"));
        damaged("sheet.streams", json -> json.remove("streams"));
        damaged("sheet.streams[1]", json -> stream(json, "S-1").addProperty("id", "F-1"));
        damaged("sheet.streams[0].name", json -> stream(json, "F-1").remove("name"));
        damaged("sheet.streams[1].from.port", json -> stream(json, "S-1").getAsJsonObject("from").remove("port"));
        damaged("sheet.streams[1].to", json -> stream(json, "S-1").addProperty("to", 3));
        damaged("sheet.streams[0].route[0].x", json -> stream(json, "F-1").getAsJsonArray("route").get(0).getAsJsonObject().remove("x"));
        damaged("sheet.streams[0].feed.temperature", json -> feed(json).addProperty("temperature", "hot"));
        damaged("sheet.streams[0].feed.amounts", json -> feed(json).remove("amounts"));
        damaged("sheet.streams[0].feed.amounts.Water", json -> feed(json).getAsJsonObject("amounts").addProperty("Water", -1));
        damaged("sheet.streams[0].feed.solids[0].micrometres", json -> feed(json).getAsJsonArray("solids").get(0).getAsJsonObject().addProperty("micrometres", "wide"));
        damaged("sheet.streams[0].feed.solids[0].mass_flow", json -> feed(json).getAsJsonArray("solids").get(0).getAsJsonObject().remove("mass_flow"));
        damaged("sheet.settings", json -> json.getAsJsonObject("settings").addProperty("max_passes", 0));
        damaged("sheet.settings.flow_tolerance", json -> json.getAsJsonObject("settings").addProperty("flow_tolerance", "tight"));
    }

    // ---- Sizing's facts (sizing plan section 3.9) ----

    private static final Path SIZED = CORPUS.resolve("format-1-sizing.json");

    private static JsonObject sized() {
        try {
            return tree(Files.readAllBytes(SIZED));
        } catch (IOException missing) {
            throw new AssertionError("the sized image", missing);
        }
    }

    /** A sheet saved before sizing opens with every item on Auto and no size, and saves with no sizing section. */
    @Test void anUnsizedImageOpensOnAuto() {
        var sheet = FlowsheetFile.read(imageBytes());
        assertEquals(SizingFacts.NONE, sheet.sizing());
        assertFalse(tree(FlowsheetFile.write(sheet)).has("sizing"));
    }

    /** Each mode, size, type and hold, a line's service and length, and the basis, as a sized sheet holds them. */
    @Test void aSizedImageKeepsItsFacts() {
        var sheet = FlowsheetFile.read(bytes(sized()));
        var facts = sheet.sizing();
        assertEquals(new DesignBasis(1.2, 80, 50), facts.basis());
        assertEquals(SizingFacts.Mode.FIXED, facts.unit("P-1").mode());
        assertEquals(new PumpSizing.Size(.15), facts.unit("P-1").size());
        assertEquals(SizingFacts.Mode.FIXED, facts.unit("T-1").mode());
        assertNull(facts.unit("T-1").size(), "a column's size is its door's diameter");
        assertEquals(new ExchangerSizing.Size(true, 1, 1.25, 4), facts.unit("T-1/reboiler").size());
        assertEquals(new CompressorSizing.Size(UnitType.PISTON, .6, 3), facts.unit("C-1").size());
        assertEquals(UnitType.SPHERE, facts.unit("K-1").type());
        assertEquals(48, facts.unit("K-1").hold(), 0);
        var line = facts.line("S-1");
        assertEquals(Service.LIQUID, line.service());
        assertEquals(120, line.length(), 0);
        assertEquals(new LineSizing.Size(.3, 1), line.size());
        assertEquals(SizingFacts.LineFacts.AUTO, facts.line("F"), "a line with no row is on Auto");
        assertEquals(sheet, FlowsheetFile.read(FlowsheetFile.write(sheet)));
    }

    /**
     * S3b: an air cooler's banks in parallel, one where absent, and a column door's member counts; a count past the most a member
     * step takes is damage.
     */
    @Test void membersInParallelKeepTheirCounts() throws IOException {
        var sheet = FlowsheetFile.read(Files.readAllBytes(CORPUS.resolve("format-1-counts.json")));
        var facts = sheet.sizing();
        assertEquals(2, ((AirCoolerSizing.Size) facts.unit("A-1").size()).banks());
        assertEquals(3, facts.unit("T-1/condenser").size().count());
        assertEquals(2, facts.unit("T-1/reboiler").size().count());
        var door = sheet.units().get(2).params();
        assertEquals(3, door.number("condenser_units"));
        assertEquals(2, door.number("kettle_units"));
        assertEquals(1, ((AirCoolerSizing.Size) FlowsheetFile.read(bytes(sized())).sizing().unit("A-1").size()).banks(), "a size saved before the count is one bank");
        damagedFact("sheet.sizing.units.A-1.size", sizing -> fact(sizing, "units", "A-1").getAsJsonObject("size").addProperty("banks", 7));
    }

    /** W10: an ejector's suction is a fact, the tile's and a column block member's, and a column door keeps its ejector's pressures. A door saved before them reads the tile's defaults ({@code ColumnEjectorTest}). */
    @Test void anEjectorKeepsItsSuctionAndItsPressures() throws IOException {
        var sheet = FlowsheetFile.read(Files.readAllBytes(CORPUS.resolve("format-1-ejector.json")));
        assertEquals(new EjectorSizing.Size(.25), sheet.sizing().unit("J-1").size());
        assertEquals(new EjectorSizing.Size(.8), sheet.sizing().unit("T-1/ejector").size());
        var door = sheet.units().get(1).params();
        assertEquals(1.2e5, door.number("ejector_discharge"));
        assertEquals(1e6, door.number("ejector_motive"));
        var json = JsonParser.parseString(Files.readString(CORPUS.resolve("format-1-ejector.json"))).getAsJsonObject();
        fact(json.getAsJsonObject("sizing"), "units", "J-1").getAsJsonObject("size").addProperty("suction", -1);
        var refusal = assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(bytes(json)));
        assertEquals(List.of("sheet.sizing.units.J-1.size"), refusal.arguments(), "a suction no ejector has is damage");
    }

    /**
     * C2: the Cooler keeps its type key, Water with an exchanger's size, and an air-then-water column door its trim's shells; a door
     * saved before the count reads one shell.
     */
    @Test void aCoolerKeepsItsTypeAndADoorItsTrim() throws IOException {
        byte[] bytes = Files.readAllBytes(CORPUS.resolve("format-1-coolers.json"));
        var sheet = FlowsheetFile.read(bytes);
        assertEquals(UnitType.WATER_COOLER, sheet.sizing().unit("A-1").type());
        assertEquals(new ExchangerSizing.Size(false, 1, 1., 6), sheet.sizing().unit("A-1").size());
        assertEquals(UnitType.AIR_COOLER, sheet.sizing().unit("A-2").type());
        var door = sheet.units().get(2).params();
        assertEquals("AIR_THEN_WATER", door.text("condenser", ""));
        assertEquals(3, door.number(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.TRIM_UNITS));
        assertEquals(new ExchangerSizing.Size(false, 3, 1.25, 5), sheet.sizing().unit("T-1/trim").size());
        assertEquals(tree(bytes), tree(FlowsheetFile.write(sheet)), "the count and the key write back");
        // A whole door reads the count, one where absent; a count past a member step's most is refused by name.
        var whole = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.from(CATALOG.presets().column(CATALOG.presets().network().defaultColumnPreset()).input(CATALOG), com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.AIR_THEN_WATER);
        assertEquals(1, com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(whole).units().trim(), "one shell where absent");
        whole.json().addProperty("trim_units", 3);
        assertEquals(3, com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(whole).units().trim());
        whole.json().addProperty("trim_units", 7);
        assertEquals(List.of("trim_units"), assertThrows(FlowsheetFailure.class, () -> com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(whole)).arguments());
    }

    /**
     * C2: a unit saved as an air cooler reads as a Cooler held on Air, its kept size with it, and writes back under today's name; a
     * key it already holds stands.
     */
    @Test void anAirCoolerReadsAsACoolerHeldOnAir() throws IOException {
        var json = tree(Files.readAllBytes(CORPUS.resolve("format-1-coolers.json")));
        unit(json, "A-1").addProperty("type", "air_cooler");
        unit(json, "A-2").addProperty("type", "air_cooler");
        var units = json.getAsJsonObject("sizing").getAsJsonObject("units");
        units.remove("A-1");
        var kept = JsonParser.parseString("{\"mode\": \"FIXED\", \"size\": {\"kind\": \"AIR_COOLER\", \"width\": 2.0, \"length\": 5, \"parallel\": 2, \"series\": 1}}").getAsJsonObject();
        units.add("A-1", kept);
        units.getAsJsonObject("A-2").addProperty("type", "WATER_COOLER");
        var sheet = FlowsheetFile.read(bytes(json));
        assertEquals("cooler", sheet.units().get(0).type());
        assertEquals(UnitType.AIR_COOLER, sheet.sizing().unit("A-1").type());
        assertEquals(SizingFacts.Mode.FIXED, sheet.sizing().unit("A-1").mode());
        assertEquals(new AirCoolerSizing.Size(2, 5, new com.wormzjl.icheme.science.equipment.ProcessUnit.Bank(2, 1)), sheet.sizing().unit("A-1").size(), "its kept size stays");
        assertEquals(UnitType.WATER_COOLER, sheet.sizing().unit("A-2").type(), "a key it holds stands");
        var written = tree(FlowsheetFile.write(sheet));
        assertEquals("cooler", unit(written, "A-1").get("type").getAsString());
        assertEquals("AIR_COOLER", written.getAsJsonObject("sizing").getAsJsonObject("units").getAsJsonObject("A-1").get("type").getAsString());
        assertEquals(sheet, FlowsheetFile.read(FlowsheetFile.write(sheet)));
        var bare = image();
        var row = new JsonObject();
        row.addProperty("id", "A-9");
        row.addProperty("type", "air_cooler");
        row.addProperty("name", "E-9");
        row.addProperty("x", 0);
        row.addProperty("y", 0);
        row.add("params", new JsonObject());
        bare.getAsJsonArray("units").add(row);
        assertEquals(UnitType.AIR_COOLER, FlowsheetFile.read(bytes(bare)).sizing().unit("A-9").type(), "a sheet never sized gains the key");
    }

    private static void damagedFact(String where, Consumer<JsonObject> damage) {
        var json = sized();
        damage.accept(json.getAsJsonObject("sizing"));
        var refusal = assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(bytes(json)), where);
        assertEquals("file.damaged", refusal.key(), where);
        assertEquals(List.of(where), refusal.arguments());
    }

    private static JsonObject fact(JsonObject sizing, String rows, String id) {
        return sizing.getAsJsonObject(rows).getAsJsonObject(id);
    }

    @Test void aDamagedFactRefusesTheFileAndSaysWhere() {
        damagedFact("sheet.sizing.basis", sizing -> sizing.getAsJsonObject("basis").addProperty("design_flow", 0));
        damagedFact("sheet.sizing.units.P-1.mode", sizing -> fact(sizing, "units", "P-1").addProperty("mode", "MANUAL"));
        damagedFact("sheet.sizing.units.K-1.type", sizing -> fact(sizing, "units", "K-1").addProperty("type", "TEAPOT"));
        damagedFact("sheet.sizing.units.K-1.hold", sizing -> fact(sizing, "units", "K-1").addProperty("hold", -1));
        damagedFact("sheet.sizing.units.E-1.size.kind", sizing -> fact(sizing, "units", "E-1").getAsJsonObject("size").addProperty("kind", "FLANGE"));
        damagedFact("sheet.sizing.units.E-1.size.shells", sizing -> fact(sizing, "units", "E-1").getAsJsonObject("size").addProperty("shells", "two"));
        damagedFact("sheet.sizing.units.E-1.size", sizing -> fact(sizing, "units", "E-1").getAsJsonObject("size").addProperty("shells", 0));
        damagedFact("sheet.sizing.lines.S-1.length", sizing -> fact(sizing, "lines", "S-1").addProperty("length", 0));
        damagedFact("sheet.sizing.lines.S-1.service", sizing -> fact(sizing, "lines", "S-1").addProperty("service", "STEAM_AND_ICE"));
        damagedFact("sheet.sizing.lines.S-2.size.runs", sizing -> fact(sizing, "lines", "S-2").getAsJsonObject("size").addProperty("runs", 1.5));
    }

    @Test void aSettingOfADoorIsTheRunsAndNotTheFiles() {
        var json = image();
        unit(json, "V-1").getAsJsonObject("params").addProperty("pressure", "high");
        var run = run(FlowsheetFile.read(bytes(json)));
        assertEquals(FlowsheetRun.Outcome.FAILED, run.outcomes().get("V-1"));
        assertTrue(logged(run, "unit.setting"), () -> "log " + run.log());
    }

    @Test void anAbsentSettingIsItsDefault() {
        var json = image();
        json.remove("settings");
        assertEquals(FlowsheetSolver.Settings.DEFAULT, FlowsheetFile.read(bytes(json)).settings());
        var one = image();
        var settings = new JsonObject();
        settings.addProperty("max_passes", 12);
        one.add("settings", settings);
        var read = FlowsheetFile.read(bytes(one)).settings();
        assertEquals(12, read.maxPasses());
        assertEquals(FlowsheetSolver.Settings.DEFAULT.flowTolerance(), read.flowTolerance());
    }

    @Test void aNewerFormatIsRefused() {
        var json = image();
        json.addProperty("format", FlowsheetFile.FORMAT + 1);
        json.remove("units");
        var refusal = assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(bytes(json)));
        assertEquals("file.newer", refusal.key());
        assertEquals(List.of(String.valueOf(FlowsheetFile.FORMAT + 1), String.valueOf(FlowsheetFile.FORMAT)), refusal.arguments());
    }

    @Test void aCutFileIsRefused() {
        byte[] bytes = imageBytes();
        int whole = new String(bytes, StandardCharsets.UTF_8).lastIndexOf('}');
        for (int length = 0; length <= whole; length += 37) {
            byte[] cut = Arrays.copyOf(bytes, length);
            assertEquals("file.damaged", assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(cut), () -> "cut at " + cut.length).key());
        }
    }

    @Test void badBytesAreRefused() {
        byte[] bytes = imageBytes();
        bytes[new String(bytes, StandardCharsets.ISO_8859_1).indexOf("FEED VALVE")] = (byte) 0xff;
        assertEquals(List.of("text"), assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read(bytes)).arguments());
        assertEquals("file.damaged", assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read("no sheet at all".getBytes(StandardCharsets.UTF_8))).key());
        assertEquals("file.damaged", assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.read("[1]".getBytes(StandardCharsets.UTF_8))).key());
    }

    @Test void aRefusedFileIsLeftAsItIs() throws IOException {
        Files.createDirectories(FOLDER);
        var file = FOLDER.resolve("damaged.json");
        byte[] bytes = Arrays.copyOf(imageBytes(), 200);
        Files.write(file, bytes);
        var then = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, then);
        assertThrows(FlowsheetFailure.class, () -> FlowsheetFile.open(file));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(then, Files.getLastModifiedTime(file));
    }

    @Test void openingNeverWrites() throws IOException {
        Files.createDirectories(FOLDER);
        var file = FOLDER.resolve("older-form.json");
        byte[] bytes = imageBytes();
        Files.write(file, bytes);
        var then = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(file, then);
        FlowsheetFile.open(file);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(then, Files.getLastModifiedTime(file));
    }

    @Test void aSaveLandsWhole() throws IOException {
        var file = FOLDER.resolve("saves/sheet.json");
        Files.deleteIfExists(file);
        var sheet = FlowsheetFile.read(imageBytes());
        FlowsheetFile.save(file, sheet);
        assertEquals(sheet, FlowsheetFile.open(file));
        var other = new Flowsheet(sheet.basis(), List.of(), List.of(), sheet.settings());
        FlowsheetFile.save(file, other);
        assertEquals(other, FlowsheetFile.open(file), "a save replaces what stood there");
        assertFalse(Files.exists(FOLDER.resolve("saves/sheet.json.part")), "and leaves nothing beside it");
    }
}

package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every door and panel's rows (W6_SCREEN_PLAN.md section 8), solved and not, at every width from 300 to 790 in steps of 7: inside their area, plates in one line, nothing cut, boxes in line. The texts they
 * ask for, and every key the flowsheet's sources name, stand in the language file.
 */
class SimulatorColumnsTest {
    private static final Flowsheet SHEET = sheet();
    private static final FlowsheetRun RUN = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(SHEET, SheetFixture.CATALOG, () -> {
    });

    /** One of every lit type a door draws, in a line that runs. */
    private static Flowsheet sheet() {
        var names = List.of("pump", "valve", "exchanger", "fired_heater", "cooler", "drum", "mixer", "splitter", "filter", "storage_tank", "kettle", "compressor");
        var units = new ArrayList<Flowsheet.Unit>();
        int x = 40;
        for (var type : names) {
            units.add(new Flowsheet.Unit("u-" + type, type, type.toUpperCase(java.util.Locale.ROOT), x, 60, SheetEditor.defaults(type, SheetFixture::column)));
            x += 100;
        }
        var streams = new ArrayList<Flowsheet.Stream>();
        streams.add(new Flowsheet.Stream("feed", "FEED", null, new Flowsheet.End("u-pump", "in"), SheetFixture.water(100), List.of(new Flowsheet.Point(10, 70)), new JsonObject()));
        streams.add(new Flowsheet.Stream("a", "A", new Flowsheet.End("u-pump", "out"), new Flowsheet.End("u-valve", "a"), null, List.of(), new JsonObject()));
        streams.add(new Flowsheet.Stream("b", "B", new Flowsheet.End("u-valve", "b"), null, null, List.of(new Flowsheet.Point(300, 300)), new JsonObject()));
        return new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
    }

    private static SheetContext context(SheetFixture.Texts texts, boolean solved) {
        var editor = SheetFixture.editor(SHEET);
        return new SheetContext(editor, new SheetState(editor.sheet(), solved ? SHEET : null, solved ? RUN : null, 1.2, null), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
    }

    private static List<Door> doors(SheetContext x) {
        var doors = new ArrayList<Door>();
        for (var unit : x.editor().sheet().units())
            for (int tab = 0; tab < 2; tab++) {
                var view = new Door.View();
                view.tab = tab;
                doors.add(UnitDoors.door(x, unit.id(), view));
            }
        for (var id : List.of("feed", "a", "b"))
            for (int tab = 0; tab < 3; tab++) {
                var view = new Door.View();
                view.tab = tab;
                doors.add(StreamDoors.door(x, id, view));
            }
        return doors;
    }

    private static List<Door> columnDoors(SheetFixture.Texts texts) {
        var units = List.of(new Flowsheet.Unit("t", "column", "T-101", 300, 40, SheetFixture.column()));
        var streams = List.of(new Flowsheet.Stream("feed", "FEED", null, new Flowsheet.End("t", "feed"), SheetFixture.water(100), List.of(new Flowsheet.Point(100, 200)), new JsonObject()));
        var editor = SheetFixture.editor(new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT));
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
        var doors = new ArrayList<Door>();
        for (int tab = 0; tab < 4; tab++)
            for (var connection : CalculatorColumn.Connection.values()) {
                var view = new Door.View();
                view.tab = tab;
                view.connection = connection;
                doors.add(ColumnDoors.door(x, "t", view));
            }
        return doors;
    }

    static void assertFits(Measure m, Column column, Rect area, String what) {
        for (var p : column.rows()) {
            assertTrue(p.rect().x() >= area.x() && p.rect().right() <= area.right(), what + ": a row inside its area");
            for (var t : p.texts()) assertTrue(m.width(t) <= p.rect().w(), what + ": a text cut: " + t);
            for (var e : p.slots().entrySet()) assertTrue(e.getValue().inside(p.rect()), what + ": " + e.getKey() + " " + e.getValue() + " inside its row " + p.rect());
            if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), what + ": a plate in one line: " + g.title());
        }
        var boxes = new java.util.HashSet<List<Integer>>();
        var keys = new java.util.HashSet<String>();
        for (var p : column.rows()) {
            for (var e : p.slots().entrySet()) {
                assertTrue(keys.add(e.getKey()), what + ": one widget a key: " + e.getKey());
                boolean stepper = p.row() instanceof Column.Setting setting && !e.getKey().equals(setting.key());
                if (!stepper) assertTrue(e.getValue().w() >= 40, what + ": a widget too narrow: " + e.getKey() + " " + e.getValue());
            }
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Reading || p.row() instanceof Column.Setting) {
                var box = p.slots().values().iterator().next();
                boxes.add(List.of(box.x(), box.w()));
                assertTrue(m.width(p.texts().getLast()) <= Column.UNIT, what + ": a unit wider than its place");
            }
        }
        assertTrue(boxes.size() <= 1, what + ": boxes out of line " + boxes);
    }

    /** A column of fixed width, as it stands and less the bar a long one takes. */
    static void assertFixedFits(Measure m, List<? extends Column.Row> rows, int width, String what) {
        for (int w : new int[]{width, width - ScrollView.BAR - 4}) assertFits(m, Column.place(rows, 0, 0, w, m, Column.Metrics.COMPACT), new Rect(0, 0, w, 400), what + " at " + w);
    }

    static void assertDoorFits(Measure m, Door door, String what) {
        for (int width = 300; width <= 790; width += 7) {
            var areas = DoorFrame.areas(door, width, SimulatorFrame.DOOR_H);
            var columns = DoorFrame.columns(door, width);
            assertEquals(columns.size(), areas.size(), what + " at " + width);
            for (int i = 0; i < areas.size(); i++) {
                var area = areas.get(i);
                assertFits(m, Column.place(columns.get(i), area.x(), area.y(), area.w(), m, Column.Metrics.COMPACT), area, what + " at " + width);
            }
        }
    }

    @Test
    void everyDoorFitsAtEveryWidth() {
        assertDoorsFit(new SheetFixture.Texts());
    }

    @Test
    void everyDoorFitsAtEveryWidthInChinese() {
        assertDoorsFit(SheetFixture.Texts.chinese());
    }

    private static void assertDoorsFit(SheetFixture.Texts texts) {
        for (boolean solved : new boolean[]{false, true})
            for (var door : doors(context(texts, solved))) assertDoorFits(texts.measure, door, door.title() + (solved ? ", solved" : ""));
        for (var door : columnDoors(texts)) assertDoorFits(texts.measure, door, door.title() + " tab " + door.tab());
        assertEveryLineThere(texts);
    }

    /** The batch's own lines have a place in every language; a door borrows older lines, which the game draws in English where a language lacks them. */
    private static void assertEveryLineThere(SheetFixture.Texts texts) {
        assertEquals(Set.of(), texts.missing);
        assertEquals(List.of(), texts.inEnglish.stream().filter(key -> key.startsWith("gui.icheme.flowsheet.")).toList());
    }

    @Test
    void everyPanelAndPageColumnFits() {
        assertPanelsFit(new SheetFixture.Texts());
    }

    @Test
    void everyPanelAndPageColumnFitsInChinese() {
        assertPanelsFit(SheetFixture.Texts.chinese());
    }

    private static void assertPanelsFit(SheetFixture.Texts texts) {
        var m = texts.measure;
        for (boolean solved : new boolean[]{false, true}) {
            var x = context(texts, solved);
            int panel = SimulatorFrame.PANEL - 18;
            for (var unit : x.editor().sheet().units()) assertFixedFits(m, SelectionColumn.of(x, new SheetGestures.Selection(true, unit.id())).rows(), panel, unit.id());
            for (var id : List.of("feed", "a", "b")) assertFixedFits(m, SelectionColumn.of(x, new SheetGestures.Selection(false, id)).rows(), panel, id);
            var state = new ResultsPage.State();
            for (var view : ResultsPage.View.values()) {
                state.view = view;
                var door = new Rect(10, SimulatorFrame.DOOR_TOP, SimulatorFrame.RESULT_DOOR - 10, 400);
                assertFits(m, SimulatorFrame.doorColumn(ResultsPage.rows(x, state), SimulatorFrame.RESULT_DOOR, m), door, "results " + view);
                ResultsPage.status(x, state);
            }
            var basis = new Rect(10, SimulatorFrame.DOOR_TOP, SimulatorFrame.BASIS_DOOR - 10, 400);
            assertFits(m, SimulatorFrame.doorColumn(BasisPage.rows(x), SimulatorFrame.BASIS_DOOR, m), basis, "basis");
        }
        assertEveryLineThere(texts);
    }

    /** What each kind sets, by its widgets' keys, in the door's first tab. */
    @Test
    void eachKindHasItsSetPoints() {
        var kinds = new java.util.LinkedHashMap<String, List<String>>();
        kinds.put("valve", List.of("set/target/outlet", "set/target/drop", "drop"));
        kinds.put("exchanger", List.of("set/target/outlet", "set/target/duty", "set/target/area", "set/side/tube", "set/side/shell", "tube_drop", "shell_drop"));
        kinds.put("mixer", List.of("inlets", "inlets-", "inlets+"));
        kinds.put("splitter", List.of("set/mode/shares", "set/mode/flows", "add_outlet"));
        var x = context(new SheetFixture.Texts(), false);
        kinds.forEach((type, keys) -> {
            var door = UnitDoors.door(x, "u-" + type, new Door.View());
            var slots = Column.place(door.columns().getFirst(), 10, 74, 180, SheetFixture.M, Column.Metrics.COMPACT).slots().keySet();
            for (var key : keys) assertTrue(slots.contains(key), type + " sets " + key + ": " + slots);
        });
    }

    /** Plan section 3.11: a port's hover gives its full name and what happens if it stays free. */
    @Test
    void aPortsHoverNamesItAndSaysWhatFreeMeans() {
        var texts = new SheetFixture.Texts();
        var kinds = new java.util.LinkedHashMap<String, String>();
        kinds.put("pump in", "cannot run");
        kinds.put("pump out", "leaves the sheet");
        kinds.put("mixer in/1", "nothing enters");
        kinds.put("exchanger tube_a", "a utility");
        kinds.put("column condenser_a", "a utility");
        kinds.put("column feed", "cannot run");
        for (var tile : Tiles.all()) {
            if (!tile.lit() || tile.type().equals(Tiles.FEED) || tile.type().equals(Tiles.PRODUCT)) continue;
            var units = List.of(new Flowsheet.Unit("u", tile.type(), "U-101", 100, 60, SheetEditor.defaults(tile.type(), SheetFixture::column)));
            var editor = SheetFixture.editor(new Flowsheet(SheetFixture.basis(), units, List.of(), FlowsheetSolver.Settings.DEFAULT));
            var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
            for (var place : editor.layout().box("u").places()) {
                var lines = SimulatorPage.portHover(x, place).split("\n");
                assertEquals(2, lines.length, tile.type() + " " + place.port());
                assertTrue(lines[0].startsWith("U-101, "), lines[0]);
                var want = kinds.remove(tile.type() + " " + place.port());
                if (want != null) assertTrue(lines[1].contains(want), tile.type() + " " + place.port() + ": " + lines[1]);
            }
        }
        assertEquals(Map.of(), kinds, "every case met");
        assertEquals(Set.of(), texts.missing);
        var editor = SheetFixture.editor(SheetFixture.line());
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
        assertTrue(SimulatorPage.portHover(x, editor.layout().place("p", "out")).contains("Holds S-1."));
    }

    @Test
    void everyTileAndCategoryHasItsName() {
        var texts = new SheetFixture.Texts();
        for (var category : Tiles.CATEGORIES) {
            texts.get(category.name());
            for (var tile : category.tiles()) texts.get(tile.name());
        }
        assertEquals(Set.of(), texts.missing);
    }

    private static void scan(Path dir, List<Pattern> patterns, Set<String> keys) throws IOException {
        try (var files = Files.walk(dir)) {
            for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (var pattern : patterns) {
                    var m = pattern.matcher(source);
                    while (m.find()) keys.add(m.group(1));
                }
            }
        }
    }

    /** What the builders and the science name by a literal key, on paths no door above walks. */
    @Test
    void everyKeyTheSourcesNameIsInTheLanguageFile() throws IOException {
        var keys = new TreeSet<String>();
        var root = Path.of("src/main/java/com/wormzjl/icheme");
        scan(root.resolve("client/gui/flowsheet"), List.of(Pattern.compile("x\\.text\\(\\s*\"([a-z0-9_.]+)\""), Pattern.compile("(?:\\? |: )\"((?:door|hint|results|basis|file)\\.[a-z0-9_.]+)\"")), keys);
        var full = new TreeSet<String>();
        scan(root.resolve("client/gui/flowsheet"), List.of(Pattern.compile("\"(flowsheet\\.[a-z0-9_.]+)\"")), full);
        scan(root.resolve("science/flowsheet"), List.of(Pattern.compile("new FlowsheetFailure\\(\\s*\"([a-z_.]+)\""), Pattern.compile("Note\\.(?:info|warning|error)\\([^,]+,\\s*\"([a-z_.]+)\""), Pattern.compile("\\bfail\\(\\s*\"([a-z_.]+)\"")), keys);
        assertTrue(keys.size() > 100, "the patterns still find the sources: " + keys.size());
        var missing = new TreeSet<String>();
        for (var key : keys) if (!key.endsWith(".") && !key.endsWith("_") && !SheetFixture.Texts.has("gui.icheme.flowsheet." + key)) missing.add(key);
        for (var key : full) if (!key.endsWith(".") && !key.endsWith("_") && !SheetFixture.Texts.has("gui.icheme." + key)) missing.add(key);
        assertEquals(Set.of(), missing);
    }
}

package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sheet's editor (W6_SCREEN_PLAN.md section 8): every operation changes the sheet, Undo brings back its bytes and Redo the result, and a field a later version wrote survives each one.
 * A name in use or an empty one is refused and changes nothing.
 */
class SheetEditorTest {
    private static JsonObject future() {
        var json = new JsonObject();
        json.addProperty("future", 1);
        return json;
    }

    /** {@link SheetFixture#line()} with a future field on every record and in every unit's settings. */
    private static Flowsheet marked() {
        var plain = SheetFixture.line();
        var units = new ArrayList<Flowsheet.Unit>();
        for (var u : plain.units()) {
            var params = u.params().json().deepCopy();
            params.addProperty("future", 1);
            units.add(new Flowsheet.Unit(u.id(), u.type(), u.name(), u.x(), u.y(), new Params(params), future()));
        }
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var s : plain.streams()) {
            var feed = s.feed() == null ? null : new Flowsheet.Feed(s.feed().temperature(), s.feed().pressure(), s.feed().amounts(), s.feed().solids(), future());
            var from = s.from() == null ? null : new Flowsheet.End(s.from().unit(), s.from().port(), future());
            var to = s.to() == null ? null : new Flowsheet.End(s.to().unit(), s.to().port(), future());
            streams.add(new Flowsheet.Stream(s.id(), s.name(), from, to, feed, s.route(), future()));
        }
        var settings = plain.settings();
        return new Flowsheet(plain.basis(), units, streams, new FlowsheetSolver.Settings(settings.flowTolerance(), settings.temperatureTolerance(), settings.maxPasses(), settings.lowestFactor(), settings.highestFactor(), future()), plain.sizing(), future());
    }

    /** A new type takes its own settings, so what a later version wrote in the old ones goes with them; the unit's own fields stay. */
    private static void assertMarked(Flowsheet sheet, String what) {
        assertTrue(sheet.unknown().has("future"), what + ": the sheet's");
        assertTrue(sheet.settings().unknown().has("future"), what + ": the solver's");
        for (var u : sheet.units()) {
            if (List.of("p", "v", "q").contains(u.id())) {
                assertTrue(u.unknown().has("future"), what + ": unit " + u.id());
                if (u.type().equals("pump") || u.type().equals("valve")) assertTrue(u.params().json().has("future"), what + ": settings of " + u.id());
            }
        }
        for (var s : sheet.streams())
            if (List.of("f", "s", "o").contains(s.id())) {
                assertTrue(s.unknown().has("future"), what + ": stream " + s.id());
                if (s.feed() != null) assertTrue(s.feed().unknown().has("future"), what + ": feed " + s.id());
            }
    }

    private record Edit(String name, Consumer<SheetEditor> run) {
    }

    private static List<Edit> edits() {
        var edits = new ArrayList<Edit>();
        edits.add(new Edit("add a unit", e -> assertNotNull(e.addUnit("drum", 200, 200))));
        edits.add(new Edit("move a unit", e -> assertTrue(e.moveUnit("p", 120, 96, null))));
        edits.add(new Edit("remove a unit", e -> assertTrue(e.removeUnit("q"))));
        edits.add(new Edit("set a field", e -> assertTrue(e.set("p", "pressure", 14e5, "k"))));
        edits.add(new Edit("set a word", e -> assertTrue(e.set("p", "target", "discharge", "k"))));
        edits.add(new Edit("set settings", e -> {
            var params = e.unit("p").params().json().deepCopy();
            params.addProperty("rise", 3e5);
            assertTrue(e.setParams("p", new Params(params), "k"));
        }));
        edits.add(new Edit("replace a type", e -> assertTrue(e.replaceUnit("q", "compressor"))));
        edits.add(new Edit("rename a unit", e -> assertTrue(e.rename("p", "P-900"))));
        edits.add(new Edit("rename a stream", e -> assertTrue(e.rename("s", "LINE"))));
        edits.add(new Edit("add a feed", e -> assertNotNull(e.addFeed(40, 200))));
        edits.add(new Edit("add a product", e -> assertNotNull(e.addProduct(480, 200))));
        edits.add(new Edit("add a stream", e -> assertNotNull(e.addStream(e.layout().place("q", "out"), null, 480, 217))));
        edits.add(new Edit("remove a stream", e -> assertTrue(e.removeStream("s"))));
        edits.add(new Edit("set a feed", e -> {
            var feed = e.stream("f").feed();
            assertTrue(e.setFeed("f", new Flowsheet.Feed(350, feed.pressure(), feed.amounts(), feed.solids(), feed.unknown()), "k"));
        }));
        edits.add(new Edit("reconnect a stream", e -> assertTrue(e.reconnect("s", false, e.layout().place("q", "in"), 0, 0))));
        edits.add(new Edit("cut a stream's end", e -> assertTrue(e.reconnect("s", false, null, 280, 150))));
        edits.add(new Edit("move a pill", e -> assertTrue(e.movePill("o", false, 460, 120, "k"))));
        edits.add(new Edit("move a segment", e -> {
            int[] points = e.layout().line("s").points();
            assertTrue(e.moveSegment("s", 0, 0, 16, points, "k"));
        }));
        edits.add(new Edit("solver settings", e -> {
            var s = e.sheet().settings();
            assertTrue(e.settings(new FlowsheetSolver.Settings(s.flowTolerance() * 2, s.temperatureTolerance(), s.maxPasses(), s.lowestFactor(), s.highestFactor(), s.unknown()), "k"));
        }));
        return edits;
    }

    @Test
    void everyEditUndoesAndRedoesAndKeepsWhatALaterVersionWrote() {
        for (var edit : edits()) {
            var editor = SheetFixture.editor(marked());
            byte[] before = FlowsheetFile.write(editor.sheet());
            edit.run().accept(editor);
            editor.endStep();
            byte[] after = FlowsheetFile.write(editor.sheet());
            assertFalse(Arrays.equals(before, after), edit.name() + " changes the sheet");
            assertMarked(editor.sheet(), edit.name());
            assertTrue(editor.canUndo(), edit.name());
            assertTrue(editor.undo());
            assertArrayEquals(before, FlowsheetFile.write(editor.sheet()), "Undo of " + edit.name());
            assertTrue(editor.canRedo(), edit.name());
            assertTrue(editor.redo());
            assertArrayEquals(after, FlowsheetFile.write(editor.sheet()), "Redo of " + edit.name());
        }
    }

    @Test
    void entriesUnderOneKeyAreOneStep() {
        var editor = SheetFixture.editor(SheetFixture.line());
        byte[] before = FlowsheetFile.write(editor.sheet());
        for (double value : new double[]{1e5, 12e5, 14e5}) editor.set("p", "pressure", value, "entry");
        editor.endStep();
        assertTrue(editor.undo());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()), "typing a number is one step");
    }

    @Test
    void aNameInUseOrEmptyIsRefusedAndChangesNothing() {
        var editor = SheetFixture.editor(SheetFixture.line());
        byte[] before = FlowsheetFile.write(editor.sheet());
        assertFalse(editor.rename("p", "V-101"), "a unit's name");
        assertEquals(SheetEditor.NAME_USED, editor.refusal().key());
        assertFalse(editor.rename("s", "P-101"), "a stream may not take a unit's");
        assertEquals(SheetEditor.NAME_USED, editor.refusal().key());
        assertFalse(editor.rename("p", "  "));
        assertEquals(SheetEditor.NAME_EMPTY, editor.refusal().key());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
        assertFalse(editor.canUndo());
    }

    @Test
    void tagsComeFromTheKindAndTheFirstFreeNumber() {
        var editor = SheetFixture.editor(SheetFixture.line());
        assertEquals("P-103", editor.nextName("pump"), "the first free from 101");
        String id = editor.addUnit("pump", 400, 300);
        assertEquals("P-103", editor.unit(id).name());
        assertEquals("D-101", editor.nextName("drum"));
        assertEquals("S-2", editor.nextStream());
    }

    @Test
    void aReadOnlySheetRefusesEveryEdit() {
        var editor = SheetFixture.editor(marked());
        editor.lock(true);
        byte[] before = FlowsheetFile.write(editor.sheet());
        assertNull(editor.addUnit("drum", 0, 0));
        assertEquals(SheetEditor.LOCKED, editor.refusal().key());
        assertFalse(editor.moveUnit("p", 0, 0, null));
        assertFalse(editor.removeStream("s"));
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
    }

    @Test
    void whatStartsOpenAndEntersAUnitHoldsAFeed() {
        var drawn = SheetFixture.editor(SheetFixture.line());
        var id = drawn.addStream(drawn.layout().place("q", "in"), null, 200, 217);
        assertNotNull(drawn.stream(id).feed(), "drawn from a free inlet");
        var lifted = SheetFixture.editor(SheetFixture.line());
        assertTrue(lifted.reconnect("s", true, null, 200, 40));
        assertNotNull(lifted.stream("s").feed(), "a start lifted to empty canvas");
        assertTrue(lifted.undo());
        assertNull(lifted.stream("s").feed(), "Undo takes it back");
        var removed = SheetFixture.editor(SheetFixture.line());
        assertTrue(removed.removeUnit("p"));
        assertNotNull(removed.stream("s").feed(), "its unit removed");
        var product = SheetFixture.editor(SheetFixture.line());
        var pill = product.addProduct(200, 300);
        assertNull(product.stream(pill).feed(), "a pill on its own stays a product");
        assertTrue(product.reconnect(pill, false, product.layout().place("q", "in"), 0, 0));
        assertNotNull(product.stream(pill).feed(), "until it lands on an inlet");
    }

    @Test
    void aDroppedUnitHasSettingsThatRun() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        keys.put("valve", List.of("target", "drop"));
        keys.put("exchanger", List.of("target", "side", "temperature", "duty", "area", "tube_drop", "shell_drop"));
        keys.put("cooler", List.of("target", "temperature", "duty", "drop"));
        keys.put("fired_heater", List.of("coils", "target", "temperature", "duty", "radiant_drop", "convection_drop"));
        keys.forEach((type, names) -> {
            var params = SheetEditor.defaults(type, SheetFixture::column);
            for (var name : names) assertTrue(params.json().has(name), type + " has " + name);
        });
    }
}

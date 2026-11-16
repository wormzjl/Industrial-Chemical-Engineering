package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.metallurgy.SheetMetallurgy;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.integrity.CorrosionDigest;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Materials tab (batch 2026-11-16-fouling-corrosion, WP6): its door for a wall and a valve fits in English and Chinese with every
 * text's line; Auto, Manual and a material write the sheet's facts, one step each, and a reader writes none; the status counts the
 * walls; the glass draws inside its box and a click picks a row; Send to block builds the material shown.
 */
class MaterialsPageTest {
    private static final FlowsheetRun STEADY = new FlowsheetRun(FlowsheetRun.Status.STEADY, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());

    /** H-101 on hot sour oil (carbon steel red), E-101 on a clean stream, V-101 a slurry valve. */
    private static SheetMetallurgy.Rows rows() {
        var hot = new CorrosionDigest(643.15, false, true, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        var clean = new CorrosionDigest(330, false, true, .02, 0, 0, 0, 0, 0, 0, 0, 0);
        var out = new LinkedHashMap<String, SheetMetallurgy.Row>();
        out.put("H-101", new SheetMetallurgy.Row("H-101", "fired_heater", 643.15, SheetMetallurgy.ratings(List.of(new SheetMetallurgy.StreamService("S-6", hot, 0, 0)), true), null));
        out.put("E-101", new SheetMetallurgy.Row("E-101", "exchanger", 330, SheetMetallurgy.ratings(List.of(new SheetMetallurgy.StreamService("S-2", clean, 0, 0)), true), null));
        out.put("V-101", new SheetMetallurgy.Row("V-101", "valve", 330, List.of(), SheetMetallurgy.trim(1e5, 800, .01)));
        return new SheetMetallurgy.Rows(out);
    }

    private static SheetSizing.Sized sized() {
        var units = new LinkedHashMap<String, SheetSizing.SizedUnit>();
        units.put("H-101", new SheetSizing.SizedUnit(new DrumSizing.Size(UnitType.STANDING_DRUM, 2, 4, false), Rating.NONE));
        units.put("E-101", new SheetSizing.SizedUnit(new DrumSizing.Size(UnitType.STANDING_DRUM, 2, 4, false), Rating.NONE));
        return new SheetSizing.Sized(Map.of(), units, List.of());
    }

    private static SheetContext context(SheetFixture.Texts texts, SheetEditor editor, boolean reader) {
        var last = new SimulatorHost.Finished(editor.sheet(), STEADY, 1);
        var rated = new SimulatorRunner.Rated(editor.sheet(), last, sized(), false, rows());
        var host = new SimulatorHost() {
            @Override public Finished last() {
                return last;
            }

            @Override public SimulatorRunner.Rated rated() {
                return rated;
            }

            @Override public boolean reader() {
                return reader;
            }

            @Override public boolean stores() {
                return true;
            }
        };
        if (reader) editor.lock(true, SheetEditor.LOCKED);
        return new SheetContext(editor, SheetState.of(editor.sheet(), last), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, host, List.of());
    }

    @Test
    void theDoorFitsForAWallAndAValveWithEveryTextsLine() {
        for (var texts : List.of(new SheetFixture.Texts(), SheetFixture.Texts.chinese())) {
            for (boolean reader : new boolean[]{false, true}) {
                var editor = SheetFixture.editor(SheetFixture.sample());
                var x = context(texts, editor, reader);
                var state = new MaterialsPage.State();
                for (var id : rows().rows().keySet()) {
                    state.unit = id;
                    var door = MaterialsPage.rows(x, state);
                    assertFalse(door.isEmpty(), id);
                    SimulatorColumnsTest.assertFixedFits(texts.measure, door, SimulatorFrame.MATERIALS_DOOR - 10, "materials " + id + (reader ? ", reader" : ""));
                    if (reader) for (var key : SimulatorPage.keys(door)) assertFalse(active(door, key), "a reader writes nothing: " + key);
                }
                MaterialsPage.status(x);
                MaterialsGlass.draw(new Recording(texts.measure), x, state, new Rect(189, 77, 270, 160));
            }
            if (texts.measure == SheetFixture.M) assertEquals(java.util.Set.of(), texts.missing);
        }
    }

    private static boolean active(List<Column.Row> rows, String key) {
        for (var row : rows)
            if (row instanceof Column.Group g) {
                if (active(g.rows(), key)) return true;
            } else if (row instanceof Column.Buttons b) for (var button : b.buttons()) if (button.key().equals(key) && button.active()) return true;
        return false;
    }

    @Test
    void autoManualAndAMaterialWriteTheSheetsFactsOneStepEach() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SheetFixture.sample());
        var x = context(texts, editor, false);
        var state = new MaterialsPage.State();
        state.unit = "H-101";
        var auto = rows().rows().get("H-101").auto();
        assertNotEquals(Metallurgy.CARBON_STEEL, auto, "hot sour oil moves Auto off carbon steel");
        assertEquals(auto, MaterialsPage.chosen(x, "H-101"));
        MaterialsPage.press(x, state, "materials/manual");
        assertEquals(auto, editor.sheet().sizing().unit("H-101").material(), "Manual keeps the material on show");
        MaterialsPage.press(x, state, "materials/material/NICKEL_ALLOY");
        assertEquals(Metallurgy.NICKEL_ALLOY, MaterialsPage.chosen(x, "H-101"));
        MaterialsPage.press(x, state, "materials/material/UNOBTAINIUM");
        assertEquals(Metallurgy.NICKEL_ALLOY, MaterialsPage.chosen(x, "H-101"), "an unknown name changes nothing");
        MaterialsPage.press(x, state, "materials/auto");
        assertNull(editor.sheet().sizing().unit("H-101").material());
        editor.undo();
        assertEquals(Metallurgy.NICKEL_ALLOY, editor.sheet().sizing().unit("H-101").material(), "one step each");
        // A valve has no wall: its keys write nothing.
        state.unit = "V-101";
        var before = editor.sheet().sizing();
        MaterialsPage.press(x, state, "materials/material/STAINLESS_STEEL");
        assertEquals(before, editor.sheet().sizing());
        assertFalse(MaterialsPage.press(x, state, "sizing/auto"), "another tab's key is not this tab's");
    }

    @Test
    void sendToBlockBuildsTheMaterialShown() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SheetFixture.sample());
        var x = context(texts, editor, false);
        var designs = SizingPage.designs(x);
        assertEquals(2, designs.size());
        assertEquals(rows().rows().get("H-101").auto(), designs.get(0).shape().material(), "Auto's choice as the concrete material");
        assertEquals(Metallurgy.CARBON_STEEL, designs.get(1).shape().material(), "a clean stream keeps carbon steel");
        var state = new MaterialsPage.State();
        state.unit = "E-101";
        MaterialsPage.press(x, state, "materials/material/CHROME_STEEL");
        assertEquals(Metallurgy.CHROME_STEEL, SizingPage.designs(x).get(1).shape().material());
        var lines = SizingGlass.requirements(x, sized(), "E-101");
        assertEquals(x.text("sizing.build_material", texts.get("metallurgy.chrome_steel")), lines.getLast(), "the Sizing door names the material");
    }

    @Test
    void theGlassDrawsInsideItsBoxAndAClickPicksARow() {
        var texts = new SheetFixture.Texts();
        var x = context(texts, SheetFixture.editor(SheetFixture.sample()), false);
        var state = new MaterialsPage.State();
        var box = new Rect(189, 77, 270, 300);
        var canvas = new Recording(texts.measure);
        MaterialsGlass.draw(canvas, x, state, box);
        for (var text : canvas.texts) assertTrue(text.x() + texts.measure.width(text.text()) <= box.right() + 4, "a cell inside the glass: " + text.text());
        assertTrue(canvas.texts.stream().anyMatch(t -> t.text().equals(texts.get("metallurgy.nickel_alloy"))), "the picked unit's four materials");
        // The table's head, then one row a line or more: the second row is E-101.
        String hit = null;
        for (int y = box.y(); y < box.y() + box.h() / 2 && hit == null; y++) {
            var key = MaterialsGlass.click(x, state, texts.measure, box, box.x() + 20, y);
            if (key != null && !key.equals("H-101")) hit = key;
        }
        assertEquals("E-101", hit);
        assertTrue(MaterialsGlass.pick(state, hit));
        assertEquals("E-101", state.unit);
        assertTrue(MaterialsPage.status(x).text().contains("2"), MaterialsPage.status(x).text());
    }

    /** A canvas that keeps its texts. */
    private static final class Recording implements com.wormzjl.icheme.client.gui.common.kit.Canvas {
        record Text(String text, int x, int y) {
        }

        final List<Text> texts = new ArrayList<>();
        private final com.wormzjl.icheme.client.gui.common.kit.Measure measure;

        Recording(com.wormzjl.icheme.client.gui.common.kit.Measure measure) {
            this.measure = measure;
        }

        @Override public com.wormzjl.icheme.client.gui.common.kit.Measure measure() {
            return measure;
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
        }

        @Override public void text(String text, int x, int y, int argb) {
            texts.add(new Text(text, x, y));
        }

        @Override public void text(String text, float x, float y, float scale, int argb) {
            texts.add(new Text(text, (int) x, (int) y));
        }

        @Override public void clip(Rect area) {
        }

        @Override public void unclip() {
        }
    }
}

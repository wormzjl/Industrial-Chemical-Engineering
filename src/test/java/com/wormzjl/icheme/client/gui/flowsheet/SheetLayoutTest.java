package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The sheet's places (W6_SCREEN_PLAN.md section 8): every type's ports on its box, there still after a move; a column's ports 14 px apart at every row count its door allows; a removed row's stream
 * an open pill that Undo returns to its port; hit tests that find what the painter drew.
 */
class SheetLayoutTest {
    private static Flowsheet one(String type, Params params) {
        var units = List.of(new Flowsheet.Unit("u", type, "U-101", 100, 60, params));
        return new Flowsheet(SheetFixture.basis(), units, List.of(), FlowsheetSolver.Settings.DEFAULT);
    }

    private static List<String> litTypes() {
        var types = new ArrayList<String>();
        for (var tile : Tiles.all()) if (tile.lit() && !tile.type().equals(Tiles.FEED) && !tile.type().equals(Tiles.PRODUCT)) types.add(tile.type());
        return types;
    }

    @Test
    void everyTypesPortsFallOnItsBoxAndStayThere() {
        var types = litTypes();
        assertFalse(types.isEmpty());
        for (var type : types) {
            var params = SheetEditor.defaults(type, SheetFixture::column);
            var editor = SheetFixture.editor(one(type, params));
            var box = editor.layout().box("u");
            var model = UnitTypes.model(type);
            assertEquals(model.ports(params).size(), box.places().size(), type + ": a place for every port");
            var r = box.rect();
            var before = new ArrayList<int[]>();
            for (var place : box.places()) {
                assertTrue(place.x() >= r.x() && place.x() <= r.right() && place.y() >= r.y() && place.y() <= r.bottom(), type + " " + place.port() + " on its box " + r);
                before.add(new int[]{place.x(), place.y()});
            }
            assertTrue(editor.moveUnit("u", 164, 92, null));
            var moved = editor.layout().box("u").places();
            for (int i = 0; i < moved.size(); i++) {
                assertEquals(before.get(i)[0] + 64, moved.get(i).x(), type + " stays on its box");
                assertEquals(before.get(i)[1] + 32, moved.get(i).y(), type + " stays on its box");
            }
        }
    }

    @Test
    void aTagStandsClearOfTheLeadFromATopPort() {
        boolean some = false;
        for (var type : litTypes()) {
            var layout = SheetLayout.of(one(type, SheetEditor.defaults(type, SheetFixture::column)), SheetFixture.M);
            var box = layout.box("u");
            var tag = layout.tag(box);
            for (var place : box.places())
                if (place.side() == SheetLayout.Side.TOP) {
                    some = true;
                    assertTrue(place.x() < tag.x() || place.x() >= tag.right(), type + " " + place.port() + " at " + place.x() + " runs through its tag " + tag);
                }
        }
        assertTrue(some, "a drum has a top port");
    }

    @Test
    void aLineBetweenTwoPortsOnOneFaceStandsOffIt() {
        var units = List.of(new Flowsheet.Unit("t", "column", "T-101", 200, 40, SheetFixture.column()), new Flowsheet.Unit("e", "exchanger", "E-101", 40, 300, SheetEditor.defaults("exchanger", SheetFixture::column)));
        var streams = List.of(new Flowsheet.Stream("a", "S-1", new Flowsheet.End("t", "pa/1/out"), new Flowsheet.End("t", "pa/2/in"), null, List.of(), new JsonObject()),
                new Flowsheet.Stream("b", "S-2", new Flowsheet.End("e", "shell_out"), new Flowsheet.End("e", "shell_in"), null, List.of(), new JsonObject()));
        var layout = SheetLayout.of(new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT), SheetFixture.M);
        int face = layout.place("t", "pa/1/out").x(), least = face;
        int[] side = layout.line("a").points();
        for (int i = 0; i < side.length; i += 2) least = Math.min(least, side[i]);
        assertEquals(face - SheetLayout.LOOP, least, "out from the column's side");
        var name = layout.name(layout.line("a"));
        assertTrue(name.right() <= face, "the name stands clear of the box: " + name);
        assertTrue(name.w() <= SheetLayout.LOOP, "and has the run to itself");
        int under = layout.place("e", "shell_out").y(), most = under;
        int[] foot = layout.line("b").points();
        for (int i = 1; i < foot.length; i += 2) most = Math.max(most, foot[i]);
        assertEquals(under + SheetLayout.LOOP, most, "out from the exchanger's foot");
    }

    private static JsonArray rows(JsonObject door, String key, int n) {
        var old = door.getAsJsonArray(key);
        var rows = new JsonArray();
        for (int i = 0; i < n; i++) {
            var row = old.get(i % old.size()).getAsJsonObject().deepCopy();
            row.addProperty("id", Integer.toString(i + 1));
            rows.add(row);
        }
        return rows;
    }

    @Test
    void aColumnsPortsHold14PxApartAtEveryRowCount() {
        for (var condenser : ColumnComponents.Condenser.values())
            for (double reboiler : new double[]{0, 18e6})
                for (int pa = 0; pa <= 4; pa++)
                    for (int steam = 0; steam <= 2; steam++)
                        for (int draw = 0; draw <= V3ColumnInput.MAX_SIDE_DRAWS; draw++) {
                            var door = SheetFixture.column().json().deepCopy();
                            door.addProperty("condenser", condenser.name());
                            door.addProperty("reboiler_duty", reboiler);
                            door.add("pumparounds", rows(door, "pumparounds", pa));
                            door.add("steam", rows(door, "steam", steam));
                            door.add("draws", rows(door, "draws", draw));
                            var box = SheetLayout.of(one("column", new Params(door)), SheetFixture.M).box("u");
                            var r = box.rect();
                            for (Function<SheetLayout.Place, Boolean> side : List.<Function<SheetLayout.Place, Boolean>>of(p -> p.x() == r.x(), p -> p.x() == r.right())) {
                                var ys = box.places().stream().filter(side::apply).map(SheetLayout.Place::y).sorted().toList();
                                for (int i = 1; i < ys.size(); i++) assertTrue(ys.get(i) - ys.get(i - 1) >= 14, condenser + " pa " + pa + " steam " + steam + " draw " + draw + ": " + ys);
                            }
                        }
    }

    @Test
    void aRemovedRowsStreamWaitsAsAnOpenPillAndUndoBringsItBack() {
        var door = SheetFixture.column();
        var units = List.of(new Flowsheet.Unit("t", "column", "T-101", 200, 40, door));
        var streams = List.of(new Flowsheet.Stream("k", "KERO", new Flowsheet.End("t", "draw/2"), null, null, List.of(new Flowsheet.Point(400, 120)), new JsonObject()));
        var editor = SheetFixture.editor(new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT));
        assertNull(editor.layout().line("k").start(), "on its port");
        var json = door.json().deepCopy();
        var kept = new JsonArray();
        for (var row : json.getAsJsonArray("draws")) if (!row.getAsJsonObject().get("id").getAsString().equals("2")) kept.add(row);
        json.add("draws", kept);
        assertTrue(editor.setParams("t", new Params(json), null));
        var pill = editor.layout().line("k").start();
        assertNotNull(pill, "an open pill near the unit");
        assertTrue(pill.lost());
        assertEquals("draw/2", editor.stream("k").from().port(), "the sheet keeps the end");
        assertTrue(editor.undo());
        assertNull(editor.layout().line("k").start(), "Undo lands it on its port");
    }

    @Test
    void aPillsPlaceIsTheRoutesEndAndTheRestAreCorners() {
        var layout = SheetLayout.of(SheetFixture.line(), SheetFixture.M);
        var feed = layout.line("f");
        assertEquals(40, feed.start().x());
        assertEquals(97, feed.start().y());
        assertNull(feed.end());
        assertTrue(feed.start().feed());
        var product = layout.line("o");
        assertEquals(420, product.end().x());
        assertFalse(product.end().feed());
        var wide = layout.line("s").points();
        for (int i = 0; i + 3 < wide.length; i += 2) assertTrue(wide[i] == wide[i + 2] || wide[i + 1] == wide[i + 3], "a line goes across or down");
    }

    @Test
    void aStreamWithBothEndsOpenOnOnePointIsOnePill() {
        var streams = List.of(new Flowsheet.Stream("a", "A", null, null, SheetFixture.water(1), List.of(new Flowsheet.Point(50, 50)), new JsonObject()),
                new Flowsheet.Stream("b", "B", null, null, null, List.of(new Flowsheet.Point(50, 90)), new JsonObject()));
        var layout = SheetLayout.of(new Flowsheet(SheetFixture.basis(), List.of(), streams, FlowsheetSolver.Settings.DEFAULT), SheetFixture.M);
        assertNotNull(layout.line("a").start(), "a feed's pill starts it");
        assertNull(layout.line("a").end());
        assertNull(layout.line("b").start());
        assertNotNull(layout.line("b").end(), "with no feed it is a product's");
        assertEquals(0, layout.line("a").points().length);
    }

    @Test
    void hitTestsFindWhatThePainterDrew() {
        var layout = SheetLayout.of(SheetFixture.line(), SheetFixture.M);
        var port = layout.place("p", "out");
        var onPort = layout.at(port.x(), port.y(), 1);
        assertEquals(SheetLayout.Kind.PORT, onPort.kind());
        assertEquals("out", onPort.place().port());
        assertEquals("p", onPort.unit());
        var pill = layout.line("f").start().rect();
        assertEquals(SheetLayout.Kind.PILL, layout.at(pill.x() + pill.w() / 2.0, pill.y() + pill.h() / 2.0, 1).kind());
        var name = layout.name(layout.line("s"));
        var onName = layout.at(name.x() + name.w() / 2.0, name.y() + name.h() / 2.0, 1);
        assertEquals(SheetLayout.Kind.NAME, onName.kind());
        assertEquals("s", onName.stream());
        var tag = layout.tag(layout.box("v"));
        var onTag = layout.at(tag.x() + tag.w() / 2.0, tag.y() + tag.h() / 2.0, 1);
        assertEquals(SheetLayout.Kind.NAME, onTag.kind());
        assertEquals("v", onTag.unit());
        Rect box = layout.box("q").rect();
        var onUnit = layout.at(box.x() + box.w() / 2.0, box.y() + box.h() / 2.0, 1);
        assertEquals(SheetLayout.Kind.UNIT, onUnit.kind());
        assertEquals("q", onUnit.unit());
        int[] line = layout.line("s").points();
        var onLine = layout.at(line[0] + 12, line[1], 1);
        assertEquals(SheetLayout.Kind.LINE, onLine.kind());
        assertEquals("s", onLine.stream());
        assertNull(layout.at(600, 300, 1), "empty canvas");
    }
}

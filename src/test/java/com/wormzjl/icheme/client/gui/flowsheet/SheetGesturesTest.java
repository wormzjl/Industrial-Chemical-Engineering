package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The canvas's mouse (PROCESS_SIMULATOR_PLAN.md section 3.5, W6_SCREEN_PLAN.md section 8), each row of the gesture table and the reconnect and drawing rules, asserted on the sheet that results.
 * The view stands at zoom 1 from the origin of a large box, so a design point is its canvas point.
 */
class SheetGesturesTest {
    private SheetEditor editor;
    private SheetView view;
    private SheetGestures gestures;
    private List<SheetGestures.Selection> doors;
    private long time;

    @BeforeEach
    void open() {
        editor = SheetFixture.editor(SheetFixture.line());
        view = new SheetView();
        view.layout(new Rect(0, 0, 900, 500));
        doors = new ArrayList<>();
        gestures = new SheetGestures(editor, view, doors::add);
        time = 10_000;
    }

    private double cx(double x) {
        return view.canvasX(x);
    }

    private double cy(double y) {
        return view.canvasY(y);
    }

    private void click(double x, double y, int button) {
        gestures.press(cx(x), cy(y), button, time);
        gestures.release(cx(x), cy(y), button);
        time += 1000;
    }

    private void twice(double x, double y) {
        gestures.press(cx(x), cy(y), 0, time);
        gestures.release(cx(x), cy(y), 0);
        gestures.press(cx(x), cy(y), 0, time + 100);
        gestures.release(cx(x), cy(y), 0);
        time += 1000;
    }

    private void drag(double x0, double y0, double x1, double y1) {
        gestures.press(cx(x0), cy(y0), 0, time);
        gestures.drag(cx(x1), cy(y1));
        gestures.release(cx(x1), cy(y1), 0);
        time += 1000;
    }

    private Rect box(String unit) {
        return editor.layout().box(unit).rect();
    }

    private double midX(String unit) {
        return box(unit).x() + box(unit).w() / 2.0;
    }

    private double midY(String unit) {
        return box(unit).y() + box(unit).h() / 2.0;
    }

    @Test
    void aLeftClickSelectsAUnitAndAStreamAndEmptyCanvasCloses() {
        click(midX("p"), midY("p"), 0);
        assertEquals(new SheetGestures.Selection(true, "p"), gestures.selected());
        assertEquals(100, editor.unit("p").x(), "a click does not move it");
        int[] line = editor.layout().line("s").points();
        click(line[0] + 14, line[1], 0);
        assertEquals(new SheetGestures.Selection(false, "s"), gestures.selected());
        assertTrue(editor.stream("s").route().isEmpty(), "a click does not bend it");
        click(600, 400, 0);
        assertNull(gestures.selected());
    }

    @Test
    void aDoubleClickOpensADoorOnAUnitAPillAndALine() {
        twice(midX("p"), midY("p"));
        assertEquals(List.of(new SheetGestures.Selection(true, "p")), doors);
        var pill = editor.layout().line("f").start();
        twice(pill.x(), pill.y());
        assertEquals(new SheetGestures.Selection(false, "f"), doors.getLast());
        int[] line = editor.layout().line("s").points();
        twice(line[0] + 14, line[1]);
        assertEquals(new SheetGestures.Selection(false, "s"), doors.getLast());
    }

    @Test
    void aDoubleClickOnANameRenamesInPlace() {
        var tag = editor.layout().tag(editor.layout().box("p"));
        twice(tag.x() + 2, tag.y() + 2);
        assertEquals(SheetGestures.Mode.NAME, gestures.mode());
        assertEquals("P-101", gestures.naming().text());
        gestures.typed("FEED-PUMP");
        assertTrue(gestures.commit("FEED-PUMP"));
        assertEquals("FEED-PUMP", editor.unit("p").name());
        assertTrue(doors.isEmpty());
        var name = editor.layout().name(editor.layout().line("s"));
        twice(name.x() + 2, name.y() + 2);
        assertEquals("S-1", gestures.naming().text());
        assertTrue(gestures.commit("LINE"));
        assertEquals("LINE", editor.stream("s").name());
    }

    @Test
    void aUsedNameIsRefusedAndTheEditorSaysSo() {
        var tag = editor.layout().tag(editor.layout().box("p"));
        twice(tag.x() + 2, tag.y() + 2);
        assertFalse(gestures.commit("V-101"));
        assertEquals("P-101", editor.unit("p").name());
        assertEquals(SheetEditor.NAME_USED, editor.refusal().key());
    }

    @Test
    void aDragMovesAUnitOnTheGridInOneStep() {
        drag(midX("p"), midY("p"), midX("p") + 33, midY("p") + 17);
        assertEquals(136, editor.unit("p").x(), "the 8 px grid");
        assertEquals(96, editor.unit("p").y());
        assertTrue(editor.undo());
        assertEquals(100, editor.unit("p").x(), "one Undo");
    }

    @Test
    void aDragOnALineMovesItsSegment() {
        int[] line = editor.layout().line("s").points();
        int middle = 0;
        for (int i = 0; i + 3 < line.length; i += 2) if (Math.abs(line[i + 2] - line[i]) > Math.abs(line[middle + 2] - line[middle])) middle = i;
        double x = (line[middle] + line[middle + 2]) / 2.0 + 30, y = line[middle + 1];
        drag(x, y, x, y + 24);
        assertFalse(editor.stream("s").route().isEmpty(), "the corners are written");
        int[] moved = editor.layout().line("s").points();
        boolean found = false;
        for (int i = 1; i < moved.length; i += 2) found |= moved[i] == line[middle + 1] + 24;
        assertTrue(found, "the segment stands 24 lower: " + java.util.Arrays.toString(moved));
        assertEquals(line[0], moved[0], "the ports stay");
        assertEquals(line[1], moved[1], "the ports stay");
    }

    @Test
    void aDragOnAPillMovesIt() {
        var pill = editor.layout().line("o").end();
        drag(pill.x(), pill.y(), pill.x() + 44, pill.y() + 39);
        var moved = editor.layout().line("o").end();
        assertEquals(464, moved.x());
        assertEquals(136, moved.y());
    }

    @Test
    void aRightClickOnAUnitDoesNothing() {
        byte[] before = FlowsheetFile.write(editor.sheet());
        click(midX("p"), midY("p"), 1);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
    }

    /** A right click near a line's far end. */
    private void lift() {
        int[] line = editor.layout().line("s").points();
        int n = line.length;
        double x = line[n - 2] - 14, y = line[n - 1];
        gestures.press(cx(x), cy(y), 1, time);
        time += 1000;
        gestures.release(cx(x), cy(y), 1);
    }

    @Test
    void aRightClickLiftsTheNearerEndAndALeftClickLandsItOnAPort() {
        lift();
        assertEquals(SheetGestures.Mode.RECONNECT, gestures.mode());
        assertEquals("s", gestures.lifted());
        var overlay = gestures.overlay();
        assertEquals("s", overlay.hidden());
        assertEquals("in", overlay.freed().port());
        assertTrue(overlay.rings().stream().anyMatch(p -> p.unit().equals("q") && p.port().equals("in")), "the free inlet is ringed");
        var port = editor.layout().place("q", "in");
        click(port.x(), port.y(), 0);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        assertEquals("q", editor.stream("s").to().unit());
        assertEquals("S-1", editor.stream("s").name(), "it keeps its name");
        assertTrue(editor.undo());
        assertEquals("v", editor.stream("s").to().unit(), "Undo restores it");
    }

    @Test
    void reconnectingCancelsOnEscapeAndOnARightClick() {
        byte[] before = FlowsheetFile.write(editor.sheet());
        lift();
        assertTrue(gestures.escape());
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
        lift();
        click(600, 400, 1);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
    }

    @Test
    void aLeftClickOnEmptyCanvasEndsALiftedEndInAPill() {
        lift();
        click(240, 320, 0);
        var stream = editor.stream("s");
        assertNull(stream.to(), "an open end");
        var pill = editor.layout().line("s").end();
        assertNotNull(pill);
        assertEquals(240, pill.x());
        assertEquals(320, pill.y());
    }

    @Test
    void aPortThatCannotTakeItRefusesTheLanding() {
        byte[] before = FlowsheetFile.write(editor.sheet());
        lift();
        var outlet = editor.layout().place("q", "out");
        assertFalse(gestures.overlay().rings().stream().anyMatch(p -> p.unit().equals("q") && p.port().equals("out")), "an outlet cannot take an inlet end");
        click(outlet.x(), outlet.y(), 0);
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
    }

    @Test
    void drawingFromAFreePortLandsOnAPort() {
        var from = editor.layout().place("q", "out");
        var to = editor.layout().place("q", "in");
        drag(from.x(), from.y(), to.x(), to.y());
        var made = editor.sheet().streams().getLast();
        assertEquals("S-2", made.name());
        assertEquals("out", made.from().port());
        assertEquals("in", made.to().port());
        assertNull(gestures.selected(), "drawing selects nothing");
    }

    @Test
    void drawingToEmptyCanvasEndsInAPillAndAFreeInletStartsAFeed() {
        var out = editor.layout().place("q", "out");
        drag(out.x(), out.y(), 500, 300);
        var product = editor.sheet().streams().getLast();
        assertEquals("out", product.from().port());
        assertNull(product.to());
        var in = editor.layout().place("q", "in");
        drag(in.x(), in.y(), 200, 300);
        var feed = editor.sheet().streams().getLast();
        assertNull(feed.from());
        assertEquals("in", feed.to().port());
        assertNotNull(feed.feed(), "every open start is a feed");
    }

    @Test
    void aCarriedTileDropsWithItsNextTagAndEscapeLetsGo() {
        gestures.carry(Tiles.tile("drum"));
        gestures.drag(cx(200), cy(300));
        assertEquals("D-101", gestures.overlay().name());
        gestures.release(cx(200), cy(300), 0);
        assertTrue(editor.sheet().units().stream().anyMatch(u -> u.name().equals("D-101")));
        int units = editor.sheet().units().size();
        gestures.carry(Tiles.tile("valve"));
        gestures.escape();
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        assertEquals(units, editor.sheet().units().size());
    }

    @Test
    void aDroppedColumnCentresItsFrameOnTheCursor() {
        gestures.carry(Tiles.tile("column"));
        gestures.drag(cx(400), cy(300));
        gestures.release(cx(400), cy(300), 0);
        var made = editor.sheet().units().stream().filter(u -> u.type().equals("column")).findFirst().orElseThrow();
        var frame = editor.layout().box(made.id()).rect();
        assertEquals(400, frame.x() + frame.w() / 2.0, SheetGestures.SNAP, "across");
        assertEquals(300, frame.y() + frame.h() / 2.0, SheetGestures.SNAP, "down");
        assertEquals(ColumnBlock.WIDTH, frame.w(), "a column's frame, not its top slice");
        assertEquals(new SheetGestures.Selection(true, made.id()), gestures.selected(), "and it stands selected");
    }

    @Test
    void aReadOnlySheetRefusesEveryGestureThatEdits() {
        editor.lock(true);
        byte[] before = FlowsheetFile.write(editor.sheet());
        drag(midX("p"), midY("p"), midX("p") + 40, midY("p"));
        var free = editor.layout().place("q", "out");
        drag(free.x(), free.y(), 500, 300);
        lift();
        gestures.escape();
        gestures.carry(Tiles.tile("drum"));
        gestures.release(cx(200), cy(300), 0);
        click(midX("v"), midY("v"), 0);
        assertFalse(gestures.delete());
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
        click(midX("p"), midY("p"), 0);
        assertEquals(new SheetGestures.Selection(true, "p"), gestures.selected(), "a reader may still select");
        twice(midX("v"), midY("v"));
        assertEquals(new SheetGestures.Selection(true, "v"), doors.getLast(), "and read a door");
    }

    @Test
    void panningMovesTheViewNotTheSheet() {
        byte[] before = FlowsheetFile.write(editor.sheet());
        double x = view.canvasX(0);
        drag(600, 400, 640, 400);
        assertEquals(x + 40, view.canvasX(0), 1e-9);
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()));
    }
    // ---- A lone pill: both ends open on one route point, a line with no points (crash 2026-11-03 23:43, overlay on an empty array) ----

    /** The line sheet with a lone feed pill "lf" at (120, 400) and a lone product pill "lp" at (520, 400). */
    private void lonePills() {
        var line = SheetFixture.line();
        var streams = new ArrayList<>(line.streams());
        streams.add(new Flowsheet.Stream("lf", "LONE-FEED", null, null, SheetFixture.water(100), List.of(new Flowsheet.Point(120, 400)), new JsonObject()));
        streams.add(new Flowsheet.Stream("lp", "LONE-PROD", null, null, null, List.of(new Flowsheet.Point(520, 400)), new JsonObject()));
        editor = SheetFixture.editor(new Flowsheet(SheetFixture.basis(), line.units(), streams, FlowsheetSolver.Settings.DEFAULT));
        gestures = new SheetGestures(editor, view, doors::add);
    }

    /** A right click on the lone pill of {@code id}: it lifts the pill's end; the overlay's elbow starts at the pill. */
    private void liftLone(String id, int x, int y) {
        assertEquals(0, editor.layout().line(id).points().length, "a lone pill has no points");
        gestures.press(cx(x), cy(y), 1, time);
        time += 1000;
        gestures.release(cx(x), cy(y), 1);
        assertEquals(SheetGestures.Mode.RECONNECT, gestures.mode());
        assertEquals(id, gestures.lifted());
        gestures.drag(cx(x + 60), cy(y - 40));
        var overlay = gestures.overlay();
        assertNotNull(overlay);
        assertEquals(x, overlay.wire()[0], "the elbow starts at the pill");
        assertEquals(y, overlay.wire()[1]);
    }

    @Test
    void aLoneFeedPillsEndLandsOnAFreeOutletAndThePillStays() {
        lonePills();
        liftLone("lf", 120, 400);
        var outlet = editor.layout().place("q", "out");
        click(outlet.x(), outlet.y(), 0);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        var stream = editor.stream("lf");
        assertEquals("out", stream.from().port(), "the lifted start lands on the outlet");
        assertNull(stream.to(), "the other end stays open");
        var pill = editor.layout().line("lf").end();
        assertNotNull(pill);
        assertEquals(120, pill.x(), "at the pill");
        assertEquals(400, pill.y());
    }

    @Test
    void aLoneProductPillsEndLandsOnAFreeInlet() {
        lonePills();
        liftLone("lp", 520, 400);
        var inlet = editor.layout().place("q", "in");
        click(inlet.x(), inlet.y(), 0);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        var stream = editor.stream("lp");
        assertEquals("in", stream.to().port(), "the lifted end lands on the inlet");
        assertNull(stream.from(), "the other end stays open");
        var pill = editor.layout().line("lp").start();
        assertNotNull(pill);
        assertEquals(520, pill.x(), "at the pill");
        assertEquals(400, pill.y());
    }

    @Test
    void aLonePillsEndOnEmptyCanvasLeavesALineFromThePill() {
        lonePills();
        liftLone("lf", 120, 400);
        click(640, 304, 0);
        var line = editor.layout().line("lf");
        assertTrue(line.points().length >= 4, "a line now");
        assertEquals(640, line.start().x(), "the lifted start at the point");
        assertEquals(304, line.start().y());
        assertEquals(120, line.end().x(), "the other end at the pill");
        assertEquals(400, line.end().y());
        lonePills();
        liftLone("lp", 520, 400);
        click(640, 304, 0);
        line = editor.layout().line("lp");
        assertTrue(line.points().length >= 4, "a line now");
        assertEquals(520, line.start().x(), "the other end at the pill");
        assertEquals(640, line.end().x(), "the lifted end at the point");
    }
}

package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Port;
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

    /**
     * A drag as the game sends it, in four steps: each a mouseMoved, then a mouseDragged at the same point
     * ({@code MouseHandler.handleAccumulatedMovement}). The canvas points are taken before the press, for a pan moves the view under them.
     */
    private void drag(double x0, double y0, double x1, double y1) {
        double a = cx(x0), b = cy(y0), c = cx(x1), d = cy(y1);
        gestures.press(a, b, 0, time);
        for (int i = 1; i <= 4; i++) {
            double x = a + (c - a) * i / 4, y = b + (d - b) * i / 4;
            gestures.move(x, y);
            gestures.drag(x, y);
        }
        gestures.release(c, d, 0);
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

    /** The owner, 2026-11-04: "Clicking in empty space should drag the whole flowsheet". */
    @Test
    void aDragOnEmptyCanvasPansTheSheetAndMovesNothingOnIt() {
        assertNull(editor.layout().at(600, 400, view.zoom()), "an empty point");
        byte[] before = FlowsheetFile.write(editor.sheet());
        double x0 = view.canvasX(0), y0 = view.canvasY(0);
        drag(600, 400, 640, 370);
        assertEquals(x0 + 40, view.canvasX(0), 1e-9, "the sheet follows the cursor across");
        assertEquals(y0 - 30, view.canvasY(0), 1e-9, "and up");
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()), "no unit, segment or pill moved");
        assertFalse(editor.undo(), "a pan is no edit");
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
    }

    /**
     * The owner, 2026-11-04, "Empty inside pans": inside a column's frame only what is drawn grabs the column (its outline, tag,
     * members, ports and pipes); a drag from the empty plate pans like the rest of the canvas.
     */
    @Test
    void aColumnFramesEmptyInsidePansAndItsOutlineAndTagStillGrabIt() {
        editor = SheetFixture.editor(SheetFixture.sample());
        gestures = new SheetGestures(editor, view, doors::add);
        var layout = editor.layout();
        var frame = layout.box("T-101").block().frame();
        double ex = -1, ey = -1, bx = -1, by = -1;
        for (int y = frame.y() + 8; y < frame.bottom() - 8 && ex < 0; y += 2)
            for (int x = frame.x() + 8; x < frame.right() - 8; x += 2)
                if (layout.at(x, y, view.zoom()) == null) {
                    ex = x;
                    ey = y;
                    break;
                }
        assertTrue(ex >= 0, "the frame has an empty inside");
        for (int y = frame.y(); y <= frame.bottom() && bx < 0; y++) {
            var hit = layout.at(frame.x(), y, view.zoom());
            if (hit != null && hit.kind() == SheetLayout.Kind.UNIT) {
                bx = frame.x();
                by = y;
            }
        }
        assertTrue(bx >= 0, "the outline is the column's");

        byte[] before = FlowsheetFile.write(editor.sheet());
        double x0 = view.canvasX(0);
        drag(ex, ey, ex + 40, ey);
        assertEquals(x0 + 40, view.canvasX(0), 1e-9, "the empty inside pans");
        assertArrayEquals(before, FlowsheetFile.write(editor.sheet()), "and moves nothing");

        gestures.press(view.canvasX(bx), view.canvasY(by), 0, time);
        assertEquals(new SheetGestures.Selection(true, "T-101"), gestures.selected(), "a press on the outline takes the column");
        assertEquals(SheetGestures.Mode.MOVE_UNIT, gestures.mode());
        gestures.release(view.canvasX(bx), view.canvasY(by), 0);
        time += 1000;
        var tag = layout.tag(layout.box("T-101"));
        gestures.press(view.canvasX(tag.x() + tag.w() / 2.0), view.canvasY(tag.y() + tag.h() / 2.0), 0, time);
        assertEquals(new SheetGestures.Selection(true, "T-101"), gestures.selected(), "and on its tag");
        assertEquals(SheetGestures.Mode.MOVE_UNIT, gestures.mode());
        gestures.release(view.canvasX(tag.x() + tag.w() / 2.0), view.canvasY(tag.y() + tag.h() / 2.0), 0);
        var tower = layout.box("T-101").block().tower();
        assertEquals("T-101", layout.at(tower.x() + tower.w() / 2.0, tower.y() + tower.h() / 2.0, view.zoom()).unit(), "and on the tower");
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
        assertEquals("a", overlay.freed().port());
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

    /** A free passage's end drawn to empty canvas makes a product from either end (owner 2026-11-04, "product"); a passage is fed by landing a feed. */
    @Test
    void drawingFromAFreePassageEndToEmptyCanvasMakesAProduct() {
        var valve = editor.addUnit("valve", 600, 300);
        for (var end : List.of("a", "b")) {
            var place = editor.layout().place(valve, end);
            drag(place.x(), place.y(), place.x() + (end.equals("a") ? -80 : 80), place.y() + 60);
            var product = editor.sheet().streams().getLast();
            assertEquals(new Flowsheet.End(valve, end), product.from(), "the end drawn out of is the outlet");
            assertNull(product.to());
            assertNull(product.feed(), "a product carries no feed");
            editor.undo();
        }
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
        editor.lock(true, SheetEditor.LOCKED);
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

    /**
     * A right click on the lone pill of {@code id}: a feed's pill is its source, so its downstream end lifts and only inlets
     * ring; a product's pill is its sink, so its upstream end lifts and only outlets ring. The overlay's elbow starts at the pill.
     */
    private void liftLone(String id, int x, int y, Port.Direction rings) {
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
        assertFalse(overlay.rings().isEmpty(), "a free port takes it");
        assertTrue(overlay.rings().stream().allMatch(r -> r.direction() == rings), "only " + rings + " ports ring");
    }

    @Test
    void aLoneFeedPillsEndLandsOnAFreeInletAndThePillStaysItsSource() {
        lonePills();
        liftLone("lf", 120, 400, Port.Direction.IN);
        var inlet = editor.layout().place("q", "in");
        click(inlet.x(), inlet.y(), 0);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        var stream = editor.stream("lf");
        assertEquals("q", stream.to().unit(), "the feed now feeds the unit");
        assertEquals("in", stream.to().port());
        assertNull(stream.from(), "its source stays open");
        assertNotNull(stream.feed(), "and keeps its feed");
        var pill = editor.layout().line("lf").start();
        assertNotNull(pill);
        assertEquals(120, pill.x(), "the source pill stays");
        assertEquals(400, pill.y());
    }

    @Test
    void aLoneProductPillsEndLandsOnAFreeOutletAndThePillStaysItsSink() {
        lonePills();
        liftLone("lp", 520, 400, Port.Direction.OUT);
        var outlet = editor.layout().place("q", "out");
        click(outlet.x(), outlet.y(), 0);
        assertEquals(SheetGestures.Mode.IDLE, gestures.mode());
        var stream = editor.stream("lp");
        assertEquals("q", stream.from().unit(), "the unit now makes the product");
        assertEquals("out", stream.from().port());
        assertNull(stream.to(), "its sink stays open");
        var pill = editor.layout().line("lp").end();
        assertNotNull(pill);
        assertEquals(520, pill.x(), "the sink pill stays");
        assertEquals(400, pill.y());
    }

    @Test
    void aLonePillsEndOnEmptyCanvasLeavesALineFromThePillThatKeepsItsRole() {
        lonePills();
        liftLone("lf", 120, 400, Port.Direction.IN);
        click(640, 304, 0);
        var line = editor.layout().line("lf");
        assertTrue(line.points().length >= 4, "a line now");
        assertEquals(120, line.start().x(), "the feed's source pill stays at the pill");
        assertEquals(400, line.start().y());
        assertEquals(640, line.end().x(), "its downstream end at the point");
        assertEquals(304, line.end().y());
        assertNotNull(editor.stream("lf").feed(), "still a feed");
        lonePills();
        liftLone("lp", 520, 400, Port.Direction.OUT);
        click(640, 304, 0);
        line = editor.layout().line("lp");
        assertTrue(line.points().length >= 4, "a line now");
        assertEquals(520, line.end().x(), "the product's sink pill stays at the pill");
        assertEquals(400, line.end().y());
        assertEquals(640, line.start().x(), "its upstream end at the point");
        assertEquals(304, line.start().y());
    }
}

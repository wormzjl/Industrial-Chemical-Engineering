package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A passage's ends on the sheet (batch 2026-11-02-process-simulator, PORT_SIDES_REVIEW.md option B, owner 2026-11-04): either end of
 * a free passage takes a stream either way; once a stream holds one end, the other takes only the other role; both ends one way
 * is refused; a port that is no passage keeps its direction.
 */
final class PassageEndsTest {
    private static SheetEditor empty() {
        return SheetFixture.editor(new Flowsheet(SheetFixture.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT));
    }

    private static SheetLayout.Place place(SheetEditor editor, String unit, String end) {
        var place = editor.layout().place(unit, end);
        assertNotNull(place, unit + " " + end);
        return place;
    }

    @Test void aFreeEndTakesAStreamEitherWayAndStandsWhereItIsDrawn() {
        for (var type : List.of("valve", "filter", "cooler", "fired_heater")) {
            var editor = empty();
            var id = editor.addUnit(type, 100, 100);
            var a = place(editor, id, "a");
            var b = place(editor, id, "b");
            assertEquals(SheetLayout.Side.LEFT, a.side(), type + ": its first end on the left");
            assertEquals(SheetLayout.Side.RIGHT, b.side(), type);
            for (var end : List.of(a, b)) {
                assertTrue(editor.either(end), type);
                assertTrue(editor.takes(end, true, null) && editor.takes(end, false, null), type + " " + end.port() + " takes a start or an end");
            }
        }
    }

    /** The owner's case: a feed on the heater's right end. Its left end becomes the outlet. */
    @Test void aFeedLandsOnTheRightEndAndTheLeftEndBecomesTheOutlet() {
        var editor = empty();
        var heater = editor.addUnit("fired_heater", 200, 100);
        var feed = editor.addFeed(400, 120);
        assertTrue(editor.reconnect(feed, false, place(editor, heater, "b"), 0, 0), () -> "landed: " + editor.refusal());
        assertEquals(new Flowsheet.End(heater, "b"), editor.stream(feed).to());
        var a = place(editor, heater, "a");
        assertEquals(Port.Direction.IN, place(editor, heater, "b").direction());
        assertEquals(Port.Direction.OUT, a.direction(), "the other end is the outlet now");
        assertFalse(editor.either(a));
        assertTrue(editor.takes(a, true, null), "it takes a stream's start");
        assertFalse(editor.takes(a, false, null), "and no stream's end");
        assertEquals("in", editor.roles().streams().stream().filter(s -> s.id().equals(feed)).findFirst().orElseThrow().to().port(), "the run reads the feed as the coil's inlet");

        var second = editor.addFeed(0, 120);
        assertFalse(editor.reconnect(second, false, a, 0, 0), "a second inlet on one passage");
        assertEquals(SheetEditor.PASSAGE, editor.refusal().key());
        var product = editor.addProduct(0, 160);
        assertTrue(editor.reconnect(product, true, a, 0, 0), () -> "the outlet end takes the product: " + editor.refusal());
    }

    /** Where nothing decides, the stream leaves the end it is drawn out of (owner 2026-11-04, "product"). */
    @Test void aStreamDrawnOutOfAFreeEndLeavesIt() {
        var editor = empty();
        var valve = editor.addUnit("valve", 200, 100);
        var pill = editor.addStream(place(editor, valve, "b"), null, 400, 120);
        assertEquals(new Flowsheet.End(valve, "b"), editor.stream(pill).from(), "a pill drawn out of a free end is a product");
        assertNull(editor.stream(pill).to());
        assertNull(editor.stream(pill).feed());
        var cooler = editor.addUnit("cooler", 600, 100);
        var joined = editor.addStream(place(editor, valve, "a"), place(editor, cooler, "a"), 0, 0);
        assertNotNull(joined, () -> "drawn: " + editor.refusal());
        assertEquals(new Flowsheet.End(cooler, "a"), editor.stream(joined).from(), "a decided inlet beats the drag's direction");
        assertEquals(new Flowsheet.End(valve, "a"), editor.stream(joined).to());
        var filter = editor.addUnit("filter", 800, 100);
        var other = editor.addUnit("valve", 1000, 100);
        var free = editor.addStream(place(editor, filter, "b"), place(editor, other, "b"), 0, 0);
        assertEquals(new Flowsheet.End(filter, "b"), editor.stream(free).from(), "free to free: the dragged end is the outlet");
        assertEquals(new Flowsheet.End(other, "b"), editor.stream(free).to());
    }

    @Test void aFixedPortDecidesTheFreeEndItIsDrawnTo() {
        var editor = empty();
        var pump = editor.addUnit("pump", 0, 100);
        var valve = editor.addUnit("valve", 200, 100);
        var stream = editor.addStream(place(editor, valve, "b"), place(editor, pump, "out"), 0, 0);
        assertNotNull(stream, () -> "drawn: " + editor.refusal());
        assertEquals(new Flowsheet.End(pump, "out"), editor.stream(stream).from(), "the pump's discharge stays the start, whichever end the drag began at");
        assertEquals(new Flowsheet.End(valve, "b"), editor.stream(stream).to());
        assertEquals(Port.Direction.OUT, place(editor, valve, "a").direction());
        assertNull(editor.addStream(place(editor, valve, "a"), place(editor, editor.addUnit("pump", 400, 100), "out"), 0, 0), "an outlet end to a discharge");
        assertEquals(SheetEditor.PASSAGE, editor.refusal().key());
        var other = editor.addUnit("pump", 600, 100);
        assertNull(editor.addStream(place(editor, other, "in"), place(editor, pump, "in"), 0, 0), "two fixed inlets");
        assertEquals(SheetEditor.PORT_SIDE, editor.refusal().key());
    }

    @Test void aLiftedEndFreesItsPassage() {
        var editor = empty();
        var valve = editor.addUnit("valve", 200, 100);
        var feed = editor.addFeed(0, 120);
        assertTrue(editor.reconnect(feed, false, place(editor, valve, "a"), 0, 0));
        assertFalse(editor.takes(place(editor, valve, "b"), false, null), "b is the outlet while the feed holds a");
        assertTrue(editor.takes(place(editor, valve, "b"), false, feed), "lifting the feed frees the passage, so b takes its end");
        assertTrue(editor.reconnect(feed, false, place(editor, valve, "b"), 0, 0));
        assertEquals(Port.Direction.OUT, place(editor, valve, "a").direction());
    }

    /** A utility side whose water enters by its second end draws its arrows that way. */
    @Test void aReversedUtilitySideTurnsItsPipes() {
        var unit = new Flowsheet.Unit("T", "column", "T-1", 0, 0, SheetFixture.column());
        var door = ColumnDoor.of(unit.params());
        var plain = ColumnBlock.of(unit, door).pipes();
        var turned = ColumnBlock.of(unit, door, Set.of("condenser_a")).pipes();
        assertEquals(plain.size(), turned.size());
        int differ = 0;
        for (int i = 0; i < plain.size(); i++) if (!java.util.Arrays.equals(plain.get(i).points(), turned.get(i).points())) differ++;
        assertEquals(2, differ, "the condenser's two water pipes, and nothing else");
        assertNotNull(ColumnBlock.of(unit, door).spot("condenser_a"));
        assertEquals(ColumnBlock.of(unit, door).spot("condenser_a"), ColumnBlock.of(unit, door, Set.of("condenser_a")).spot("condenser_a"), "its ends stay where they stand");
    }
}

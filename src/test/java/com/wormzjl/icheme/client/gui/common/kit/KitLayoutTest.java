package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The GUI kit's column and scroll view (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 4): rows placed in
 * order, inside the width and apart; texts cut and wrapped by the measure; hover and click by the same placement; a
 * scroll clamped to its content; a hidden view or a point outside it takes nothing; a widget shown only wholly inside.
 */
class KitLayoutTest {
    private static final Measure M = Measure.fixed(6);

    private static List<Column.Row> every() {
        return List.of(
                new Column.Buttons(List.of(new Column.Button("a", "Tube side", false, null), new Column.Button("b", "Shell side", true, "Opens it"))),
                new Column.Line("What this side holds", Palette.MUTED),
                new Column.Choice("Hold the outlet temperature", "Sets the bypass to hold it", true, "mode:outlet"),
                new Column.Choice("Off", "The unit runs as built", false, "mode:off"),
                new Column.Field("heatTemperature", "Outlet temperature", "C", null),
                new Column.Field("lowLimit", "Never cool below", "C", "Blank for no limit"),
                new Column.Gap(6),
                new Column.Line("Acting now: the internal bypass", Palette.MUTED),
                new Column.Bar(0.25),
                new Column.Paragraph("The bypass lines are complete: the real valve acts and its flowmeter reads its flow.", Palette.ACCENT));
    }

    @Test
    void rowsArePlacedInOrderInsideTheWidthAndApart() {
        for (int width = 150; width <= 240; width++) {
            var column = Column.place(every(), 20, 74, width, M);
            int at = 74;
            var slots = new ArrayList<Rect>();
            for (var p : column.rows()) {
                assertEquals(at, p.rect().y(), "rows touch, in order, at " + width);
                assertEquals(20, p.rect().x());
                assertEquals(width, p.rect().w());
                at = p.rect().bottom();
                for (var s : p.slots().values()) {
                    assertTrue(s.inside(p.rect()), "a widget inside its row at " + width);
                    for (var other : slots) assertFalse(s.overlaps(other), "widgets apart at " + width);
                    slots.add(s);
                }
                for (var t : p.texts()) assertTrue(M.width(t) <= width, "a text inside the width at " + width + ": " + t);
            }
            assertEquals(new Rect(20, 74, width, at - 74), column.bounds());
            assertEquals(List.of("a", "b", "heatTemperature", "lowLimit"), List.copyOf(column.slots().keySet()));
        }
    }

    @Test
    void textsAreCutAndWrappedByTheMeasure() {
        assertEquals("Hold the", M.cut("Hold the outlet", 50));
        assertEquals(List.of("one two", "three"), M.wrap("one two three", 42));
        assertEquals(List.of("abcdefg", "hij"), M.wrap("abcdefghij", 42), "a word too long is cut");
        assertEquals(List.of(""), M.wrap("", 42));
        var p = Column.place(List.of(new Column.Paragraph("one two three four five six", Palette.TEXT)), 0, 0, 100, M).rows().getFirst();
        assertEquals(M.wrap("one two three four five six", 100).size() * Measure.LINE + 4, p.rect().h());
    }

    @Test
    void aRowHoversItsFullTextOrHintOutsideItsWidgets() {
        var column = Column.place(List.of(new Column.Line("A line much longer than the column is wide, cut short", Palette.TEXT),
                new Column.Line("Short", Palette.TEXT), new Column.Field("lowLimit", "Never cool below", "C", "Blank for no limit")), 0, 0, 120, M);
        assertEquals("A line much longer than the column is wide, cut short", column.hover(5, 5));
        assertNull(column.hover(5, 17), "a line not cut has no hover");
        var field = column.rows().get(2);
        assertEquals("Never cool below\nBlank for no limit", column.hover(field.rect().x() + 2, field.rect().y() + 5));
        var box = field.slots().get("lowLimit");
        assertNull(column.hover(box.x() + 2, box.y() + 2), "the box has its own tooltip");
    }

    @Test
    void aChoiceRunsItsActionOnlyInsideItsRow() {
        var column = Column.place(every(), 20, 74, 180, M);
        var choice = column.rows().get(2);
        assertEquals("mode:outlet", column.action(choice.rect().x() + 3, choice.rect().y() + 3));
        assertEquals("mode:outlet", column.action(choice.rect().right() - 1, choice.rect().bottom() - 1));
        assertEquals("mode:off", column.action(choice.rect().x() + 3, choice.rect().bottom()));
        var line = column.rows().get(1);
        assertNull(column.action(line.rect().x() + 3, line.rect().y() + 3));
        assertNull(column.action(19, choice.rect().y() + 3), "left of the column");
    }

    @Test
    void aScrollClampsToItsContentAndAHiddenViewTakesNothing() {
        var view = new ScrollView();
        view.layout(new Rect(100, 74, 150, 100), 254, 250);
        assertEquals(150, view.max());
        assertFalse(view.wheel(99, 80, -1), "left of the viewport");
        assertTrue(view.wheel(120, 80, -1));
        assertEquals(ScrollView.STEP, view.offset());
        for (int i = 0; i < 10; i++) view.wheel(256, 80, -1);
        assertEquals(150, view.offset(), "the bar takes the wheel too; clamped");
        view.layout(new Rect(100, 74, 150, 100), 254, 120);
        assertEquals(20, view.offset(), "a shorter content clamps the offset");
        assertEquals(94.0, view.contentY(74));
        view.shown(false);
        assertFalse(view.wheel(120, 80, 1));
        assertFalse(view.press(256, 80, 0));
        assertFalse(view.inside(120, 80));
        view.shown(true);
        assertTrue(view.press(256, 74, 0));
        assertEquals(0, view.offset(), "a press at the bar's top scrolls to the top");
        assertTrue(view.drag(174));
        assertEquals(20, view.offset(), "a drag to the bar's foot scrolls to the end");
        view.release();
        assertFalse(view.drag(80));
        view.layout(new Rect(100, 74, 150, 100), 254, 60);
        assertFalse(view.wheel(120, 80, -1), "content that fits does not scroll");
        assertFalse(view.press(256, 80, 0));
    }

    @Test
    void aWidgetShowsOnlyWhollyInsideTheViewport() {
        var view = new ScrollView();
        view.layout(new Rect(0, 100, 200, 100), 204, 400);
        assertTrue(view.visible(new Rect(10, 100, 58, 18)));
        assertFalse(view.visible(new Rect(10, 190, 58, 18)), "across the foot");
        view.wheel(10, 120, -1);
        assertEquals(new Rect(10, 160, 58, 18), view.onScreen(new Rect(10, 190, 58, 18)));
        assertTrue(view.visible(new Rect(10, 190, 58, 18)));
        assertFalse(view.visible(new Rect(10, 110, 58, 18)), "across the top once scrolled");
        view.shown(false);
        assertFalse(view.visible(new Rect(10, 190, 58, 18)));
    }

    @Test
    void compactMetricsPlaceTheSameRowsShorterAndATitleOnlyChoiceHoversItsLine() {
        var normal = Column.place(every(), 20, 74, 180, M);
        var compact = Column.place(every(), 20, 74, 180, M, Column.Metrics.COMPACT);
        assertTrue(compact.bounds().h() < normal.bounds().h());
        assertEquals(normal.slots().keySet(), compact.slots().keySet());
        for (var p : compact.rows()) for (var s : p.slots().values()) assertTrue(s.inside(p.rect()), "a widget inside its compact row");
        var titles = Column.place(every(), 20, 74, 180, M, new Column.Metrics(11, 12, 20, 20, 10));
        var choice = titles.rows().get(2);
        assertEquals(12, choice.rect().h());
        assertEquals("", choice.texts().get(1), "a title-only choice draws no line");
        assertEquals("Hold the outlet temperature\nSets the bypass to hold it", titles.hover(choice.rect().x() + 20, choice.rect().y() + 3));
        assertThrows(IllegalArgumentException.class, () -> new Column.Metrics(11, 20, 17, 20, 10), "a field row lower than its box");
    }

    @Test
    void aSkinDrawsTheMarksTheBarsAndTheTonesItsRowsName() {
        var texts = new ArrayList<String>();
        var calls = new ArrayList<String>();
        var canvas = new Canvas() {
            @Override
            public Measure measure() {
                return M;
            }

            @Override
            public void fill(int x0, int y0, int x1, int y1, int argb) {
            }

            @Override
            public void text(String text, int x, int y, int argb) {
                texts.add(text + "@" + Integer.toHexString(argb));
            }

            @Override
            public void text(String text, float x, float y, float scale, int argb) {
            }

            @Override
            public void clip(Rect area) {
            }

            @Override
            public void unclip() {
            }
        };
        var skin = new Skin() {
            @Override
            public int tone(int palette) {
                return palette == Palette.MUTED ? 0xFF123456 : palette;
            }

            @Override
            public void mark(Canvas c, int x, int y, boolean selected) {
                calls.add("mark " + x + "," + y + " " + selected);
            }

            @Override
            public void bar(Canvas c, Rect r, double share) {
                calls.add("bar " + r + " " + share);
            }

            @Override
            public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
                calls.add("scrollbar " + track + " " + thumb);
            }
        };
        var column = Column.place(every(), 20, 74, 180, M);
        column.draw(canvas, -10, skin);
        var choice = column.rows().get(2).rect();
        var bar = column.rows().get(8).rect();
        assertEquals(List.of("mark 20," + (choice.y() - 10) + " true", "mark 20," + (choice.bottom() - 10) + " false", "bar " + new Rect(20, bar.y() - 10, 180, 8) + " 0.25"), calls);
        assertTrue(texts.contains("What this side holds@ff123456"), "a muted line in the skin's tone: " + texts);
        var view = new ScrollView();
        view.layout(new Rect(20, 74, 180, 100), 204, 400);
        view.drawBar(canvas, skin);
        assertEquals("scrollbar " + new Rect(204, 74, ScrollView.BAR, 100) + " " + new Rect(204, 74, ScrollView.BAR, 25), calls.getLast(), "the thumb a quarter of the track");
        Column.place(List.of(new Column.Heading("Mode")), 0, 0, 150, M).draw(canvas, 0, new Skin() {
            @Override
            public void heading(Canvas c, Rect r, String text) {
                calls.add("heading " + r + " " + text);
            }

            @Override
            public void mark(Canvas c, int x, int y, boolean selected) {
            }

            @Override
            public void bar(Canvas c, Rect r, double share) {
            }

            @Override
            public void scrollbar(Canvas c, Rect track, Rect thumb, boolean dragging) {
            }
        });
        assertEquals("heading " + new Rect(0, 0, 150, 12) + " Mode", calls.getLast(), "the skin draws a heading in its row");
    }

    @Test
    void aFieldWhoseLabelDoesNotFitBesideItsBoxStacksTheLabelAboveIt() {
        var rows = List.<Column.Row>of(new Column.Field("t", "Set point", "C", null), new Column.Field("r", "Ratio to the other feed", "", null), new Column.Heading("What this side holds"));
        var column = Column.place(rows, 0, 0, 150, M);
        var inline = column.rows().get(0);
        assertEquals(24, inline.rect().h(), "54 px of label beside the box: one row");
        assertEquals(new Rect(150 - Column.FIELD_TAIL + 4, 0, Column.BOX, Column.WIDGET), inline.slots().get("t"));
        var stacked = column.rows().get(1);
        assertEquals(12 + 24, stacked.rect().h(), "138 px of label in 70 px: the label above the box");
        assertEquals(new Rect(150 - Column.FIELD_TAIL + 4, 24 + 12, Column.BOX, Column.WIDGET), stacked.slots().get("r"), "the box under its label, in line with the other boxes");
        assertEquals("Ratio to the other feed", stacked.texts().getFirst(), "the whole label on its own line");
        assertNull(stacked.hover(), "nothing cut, no hint: no hover");
        assertEquals(new Rect(0, 60, 150, 12), column.rows().get(2).rect(), "a heading is a line high");
    }

    @Test
    void aColumnRefusesWhatItCannotPlace() {
        assertThrows(IllegalArgumentException.class, () -> Column.place(every(), 0, 0, 99, M));
        assertThrows(IllegalArgumentException.class, () -> new Column.Bar(1.5));
        assertThrows(IllegalArgumentException.class, () -> new Column.Buttons(List.of()));
        var twice = List.<Column.Row>of(new Column.Field("x", "A", "", null), new Column.Field("x", "B", "", null));
        assertThrows(IllegalStateException.class, () -> Column.place(twice, 0, 0, 150, M).slots());
    }
}

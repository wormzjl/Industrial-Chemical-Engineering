package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flowsheets' shared painter (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 3) on a recording canvas: the
 * grid fitted and centred; a label drawn only where it fits, its font never below the minimum; lines, valves, circles and
 * squares inside the box they are drawn for.
 */
class FlowsheetPainterTest {
    private record Fill(int x0, int y0, int x1, int y1, int argb) {
    }

    private record Text(String text, float x, float y, float scale) {
    }

    private static final class Recording implements Canvas {
        final List<Fill> fills = new ArrayList<>();
        final List<Text> texts = new ArrayList<>();

        @Override
        public Measure measure() {
            return Measure.fixed(6);
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills.add(new Fill(x0, y0, x1, y1, argb));
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            texts.add(new Text(text, x, y, 1));
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            texts.add(new Text(text, x, y, scale));
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    }

    @Test
    void theGridIsFittedAndCentredInItsBox() {
        var c = new Recording();
        var p = FlowsheetPainter.fit(c, 444, 250, 10, 74, 222, 250, 1.0);
        assertEquals(0.5, p.scale(), 1e-12, "the width binds");
        assertEquals(10, p.ox());
        assertEquals(74 + (250 - 125) / 2, p.oy(), "centred down");
        assertEquals(p.ox() + 111, p.x(222));
        assertEquals(1, p.px(1), "a length is at least one pixel");
        assertEquals(222.0, p.designX(p.x(222)), 1e-9);
        assertEquals(0.5f, p.fontScale());
        assertTrue(p.labels());
        var small = FlowsheetPainter.fit(c, 444, 250, 0, 0, 100, 100, 1.0);
        assertEquals((float) FlowsheetPainter.MIN_FONT, small.fontScale(), "the font never below its minimum");
        assertFalse(small.labels(), "free labels give way below the font's minimum");
    }

    @Test
    void aLabelDrawsOnlyWhereItFitsAndIsAnchored() {
        var c = new Recording();
        var p = new FlowsheetPainter(c, 0, 0, 1, 1.0);
        assertTrue(p.fitted("Shell", 100, 50, 40, Palette.TEXT));
        var t = c.texts.getLast();
        assertEquals(100 - 30 / 2f, t.x(), 1e-6, "centred on its point");
        assertEquals(50 - 9 / 2f + 1, t.y(), 1e-6, "its middle on the point");
        assertFalse(p.fitted("Shell and tube", 100, 50, 40, Palette.TEXT), "84 px in 40 design units");
        assertEquals(1, c.texts.size());
        p.label("Air", 10, 10, 1, Palette.TEXT);
        assertEquals(10 - 18, c.texts.getLast().x(), 1e-6, "anchored at its right");
    }

    @Test
    void marksStayInsideTheBoxTheyAreDrawnFor() {
        var c = new Recording();
        var p = new FlowsheetPainter(c, 0, 0, 2, 1.0);
        var look = FlowsheetPainter.Look.TEAL;
        p.circle(50, 50, 10, look);
        p.box(10, 10, 30, 20, look);
        p.rounded(100, 100, 160, 130, 15, look);
        for (var f : c.fills) {
            boolean inCircle = f.x0() >= 40 && f.x1() <= 61 && f.y0() >= 40 && f.y1() <= 61;
            boolean inBox = f.x0() >= 10 && f.x1() <= 30 && f.y0() >= 10 && f.y1() <= 20;
            boolean inRounded = f.x0() >= 100 && f.x1() <= 160 && f.y0() >= 100 && f.y1() <= 130;
            assertTrue(inCircle || inBox || inRounded, "a fill outside its mark: " + f);
        }
        c.fills.clear();
        p.wire(new int[]{0, 10, 20, 10, 20, 30}, true, false, Palette.MUTED);
        // The path is (0, 20) to (40, 20) to (40, 60) in canvas pixels; the arrow's widest bar spans x 38 to 42.
        for (var f : c.fills) assertTrue(f.x0() >= 0 && f.x1() <= 43 && f.y0() >= 20 && f.y1() <= 61, "a line or its arrow outside its path: " + f);
        c.fills.clear();
        p.wire(new int[]{0, 10, 20, 10}, false, true, Palette.LINE);
        assertTrue(c.fills.size() > 10, "a utility line is dashed: " + c.fills.size() + " pieces");
        c.fills.clear();
        p.square(20, 20, 8, 0x55DDFF);
        assertEquals(new Fill(31, 31, 49, 49, FlowsheetPainter.EDGE), c.fills.get(0), "the edge one pixel round");
        assertEquals(new Fill(32, 32, 48, 48, 0xFF55DDFF), c.fills.get(1), "the class colour, opaque");
    }
}

package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Sprite;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The loops of fills draw in one batch (the owner, 2026-11-03: "A: batch the loops"): counted as the game draws them
 * (1.21.1 {@code GuiGraphics}), the sheet's grid, a circle and an LED reading are one draw call each, and the sample
 * sheet's drawing stays in its budget.
 */
class DrawCallTest {
    /**
     * Counts draw calls as the game makes them: a fill outside a batch is one, a run of fills inside a batch is one (a
     * text, a sprite or a clip ends the run), a text is one, a sprite is one.
     */
    private static final class Counting implements Canvas {
        int draws, fills, depth;
        boolean run;

        @Override
        public Measure measure() {
            return SheetFixture.M;
        }

        @Override
        public void batch(Runnable body) {
            depth++;
            try {
                body.run();
            } finally {
                if (--depth == 0) run = false;
            }
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills++;
            if (depth == 0 || !run) draws++;
            run = depth > 0;
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            draws++;
            run = false;
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            draws++;
            run = false;
        }

        @Override
        public void sprite(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
            draws++;
            run = false;
        }

        @Override
        public void clip(Rect area) {
            run = false;
        }

        @Override
        public void unclip() {
            run = false;
        }
    }

    /**
     * The sample's Flowsheet drawing, unsolved, fitted to a 900 by 440 box: 327 draw calls for its 2,528 fills when the
     * loops were batched (2026-11-03), each fill a draw call before; 10 % above that.
     */
    private static final int SAMPLE_BUDGET = 360;

    @Test
    void theGridIsOneDrawAtAnyBoxSizeAndZoom() {
        var empty = new Flowsheet(SheetFixture.basis(), List.of(), List.of(), FlowsheetSolver.Settings.DEFAULT);
        var layout = SheetLayout.of(empty, SheetFixture.M);
        var state = new SheetState(empty, null, null, 0, null);
        for (var box : List.of(new Rect(0, 0, 64, 48), new Rect(10, 20, 464, 254), new Rect(5, 5, 944, 524), new Rect(0, 0, 2000, 1200)))
            for (double zoom : new double[]{SheetView.LEAST, 0.5, 1, SheetView.MOST}) {
                var view = new SheetView();
                view.layout(box);
                view.zoom(zoom, box.x(), box.y());
                var c = new Counting();
                SheetPainter.draw(c, layout, state, view, null, null, new SheetFixture.Texts());
                assertTrue(c.fills > 1, "the grid has dots in " + box + " at zoom " + zoom);
                assertEquals(1, c.draws, c.fills + " dots in " + box + " at zoom " + zoom);
            }
    }

    @Test
    void aCircleIsOneDraw() {
        for (int r : new int[]{2, 6, 20}) {
            var c = new Counting();
            new FlowsheetPainter(c, 0, 0, 1, 1).circle(50, 50, r, FlowsheetPainter.Look.TEAL);
            assertTrue(c.fills > 2 * r, "radius " + r + " in rows");
            assertEquals(1, c.draws, "radius " + r);
        }
    }

    @Test
    void anLedReadingIsOneDraw() {
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK))
            for (String value : List.of("0", "123.4", "8888.88")) {
                // The window's bezel and glass are three fills of their own; the reading is the fourth draw.
                var c = new Counting();
                skin.meter(c, new Rect(0, 0, 80, 16), value);
                assertTrue(c.fills > 3 + 7, value + " in segments");
                assertEquals(4, c.draws, "the window and one draw for " + value);
                var lcd = new Counting();
                skin.field(lcd, new Rect(0, 0, 80, 16), value, "", false);
                assertEquals(4, lcd.draws, "an entry's " + value);
            }
    }

    @Test
    void theSampleSheetsDrawingStaysInItsBudget() {
        var sheet = SheetFixture.sample();
        var layout = SheetLayout.of(sheet, SheetFixture.M);
        var view = new SheetView();
        view.layout(new Rect(0, 0, 900, 440));
        view.fit(layout.bounds());
        var c = new Counting();
        SheetPainter.draw(c, layout, new SheetState(sheet, null, null, 0, null), view, null, null, new SheetFixture.Texts());
        System.out.println("sample sheet: " + c.draws + " draw calls, " + c.fills + " fills");
        assertTrue(c.draws <= SAMPLE_BUDGET, c.draws + " draw calls (" + c.fills + " fills) against " + SAMPLE_BUDGET);
    }
}

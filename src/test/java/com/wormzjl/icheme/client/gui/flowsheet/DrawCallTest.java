package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.HeldDraws;
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
    /** Counts draw calls as the game makes them ({@link HeldDraws}, as {@code GuiCanvas} holds a batch's texts and sprites). */
    private static final class Counting implements Canvas {
        final HeldDraws held = new HeldDraws();
        int fills, texts;

        int draws() {
            return held.draws();
        }

        @Override
        public Measure measure() {
            return SheetFixture.M;
        }

        @Override
        public void batch(Runnable body) {
            held.begin(() -> {
            });
            try {
                body.run();
            } finally {
                held.end();
            }
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills++;
            held.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            text(text, x, y, 1, argb);
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            texts++;
            held.text(x, y, x + measure().width(text) * scale, y + measure().height() * scale, () -> {
            });
        }

        @Override
        public void sprite(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
            held.sprite(x0, y0, x1, y1, () -> {
            });
        }

        @Override
        public void clip(Rect area) {
            held.clip();
        }

        @Override
        public void unclip() {
            held.clip();
        }
    }

    /**
     * The sample's Flowsheet drawing, unsolved, fitted to a 900 by 440 box: 327 draw calls for its 2,528 fills when the
     * loops were batched (2026-11-03), each fill a draw call before; 10 % above that.
     */
    private static final int SAMPLE_BUDGET = 360;
    /** The same drawing in a frame batch: 8 fill runs and 7 text runs (2026-11-04), its 45 sprites apart; 10 % above that. */
    private static final int FRAME_RUNS = 17;

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
                assertEquals(1, c.draws(), c.fills + " dots in " + box + " at zoom " + zoom);
            }
    }

    @Test
    void aCircleIsOneDraw() {
        for (int r : new int[]{2, 6, 20}) {
            var c = new Counting();
            new FlowsheetPainter(c, 0, 0, 1, 1).circle(50, 50, r, FlowsheetPainter.Look.TEAL);
            assertTrue(c.fills > 2 * r, "radius " + r + " in rows");
            assertEquals(1, c.draws(), "radius " + r);
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
                assertEquals(4, c.draws(), "the window and one draw for " + value);
                var lcd = new Counting();
                skin.field(lcd, new Rect(0, 0, 80, 16), value, "", false);
                assertEquals(4, lcd.draws(), "an entry's " + value);
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
        System.out.println("sample sheet: " + c.draws() + " draw calls, " + c.fills + " fills");
        assertTrue(c.draws() <= SAMPLE_BUDGET, c.draws() + " draw calls (" + c.fills + " fills) against " + SAMPLE_BUDGET);
    }

    /**
     * The P, T key's lines (the owner, 2026-11-04: "Add a button to show/hide boundary conditions on the GUI (P, T)"): the
     * streams' T and P lines draw on the sample's canvas, in one batch of their own: a fill run for their plates, a text run.
     */
    @Test
    void theStreamsConditionsDrawInOneBatch() {
        var sheet = SheetFixture.sample();
        var layout = SheetLayout.of(sheet, SheetFixture.M);
        var view = new SheetView();
        view.layout(new Rect(0, 0, 900, 440));
        view.fit(layout.bounds());
        var state = new SheetState(sheet, null, null, 0, null);
        var bare = new Counting();
        SheetPainter.draw(bare, layout, state, view, null, null, new SheetFixture.Texts());
        var shown = new Counting();
        SheetPainter.draw(shown, layout, state, view, null, null, new SheetFixture.Texts(), stream -> List.of("100.0 °C", "2.50 bar"));
        assertTrue(shown.texts >= bare.texts + layout.lines().size(), shown.texts + " texts against " + bare.texts + " for " + layout.lines().size() + " streams");
        assertTrue(shown.draws() <= bare.draws() + 2, shown.draws() + " draw calls against " + bare.draws());
    }

    /**
     * In the screen's frame batch (P5, 2026-11-04) the sheet's texts and sprites wait, so they no longer break its fill runs:
     * the sample's drawing, its labels and its symbols in a few runs, each sprite still a draw of its own.
     */
    @Test
    void inTheFrameBatchTheSheetsTextsNoLongerBreakItsFills() {
        var sheet = SheetFixture.sample();
        var layout = SheetLayout.of(sheet, SheetFixture.M);
        var view = new SheetView();
        view.layout(new Rect(0, 0, 900, 440));
        view.fit(layout.bounds());
        var c = new Counting();
        c.batch(() -> SheetPainter.draw(c, layout, new SheetState(sheet, null, null, 0, null), view, null, null, new SheetFixture.Texts()));
        var held = c.held;
        System.out.println("sample sheet in a frame batch: " + held.fillDraws() + " fill runs, " + held.textDraws() + " text runs, " + held.spriteDraws() + " sprites");
        assertTrue(held.fillDraws() + held.textDraws() <= FRAME_RUNS, held.fillDraws() + " fill runs and " + held.textDraws() + " text runs against " + FRAME_RUNS);
    }

    /** A help tour's stop over the simulator (batch 2026-11-12-tutorial-system, P4b): the dim, the frame and the bubble with its sentence and keys, in one batch. */
    private static final int TOUR_DRAWS = 4;

    /**
     * An open tour adds a few draw calls to the screen's frame (P4b): the stop's fills go in runs and its texts wait for them,
     * at every stop of the simulator's sheet tour, the last one with its Show me key, at the smallest and the design frame.
     */
    @Test
    void aTourStopIsAFewDraws() {
        var words = new com.wormzjl.icheme.client.gui.tutorial.TourLayout.Words("Back", "Next", "Done", "Close", "Show me");
        int most = 0;
        for (int[] size : new int[][]{{SimulatorFrame.LEAST_W, SimulatorFrame.LEAST_H}, {SimulatorFrame.DOOR_W, SimulatorFrame.DOOR_H}}) {
            var frame = new SimulatorFrame(size[0], size[1]);
            for (var lit : List.of(frame.status(), frame.palette(), SimulatorFrame.inner(frame.well()), frame.help()))
                for (boolean last : new boolean[]{false, true}) {
                    var layout = com.wormzjl.icheme.client.gui.tutorial.TourLayout.of(size[0], size[1], lit,
                            "Drag a unit from these tiles onto the sheet to add it, then join its ports with lines.", !last, last, last, words, SheetFixture.M);
                    var c = new Counting();
                    layout.draw(c, PanelSkin.DARK, "next");
                    assertTrue(c.texts > 0 && c.fills > 0);
                    most = Math.max(most, c.draws());
                }
        }
        System.out.println("a tour stop: at most " + most + " draw calls");
        assertTrue(most <= TOUR_DRAWS, most + " draw calls against " + TOUR_DRAWS);
    }
}

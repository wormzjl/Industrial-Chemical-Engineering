package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A tour stop's overlay (batch 2026-11-12-tutorial-system, P1): for lit rectangles swept over the screen at 464 by 254
 * and 320 by 240, the bubble stands inside the screen and never over the lit rectangle, its keys inside it, and the dim
 * rectangles tile the screen less the lit one.
 */
class TourLayoutTest {
    static final TourLayout.Words WORDS = new TourLayout.Words("Back", "Next", "Done", "Close", "Show me");
    static final String SENTENCE = "This meter shows how fast the cooled liquid runs out of the still.";

    @Test
    void theBubbleStandsInsideTheScreenAndBesideTheLitControl() {
        int cases = 0;
        for (var screen : new int[][]{{464, 254}, {320, 240}})
            for (var size : new int[][]{{18, 18}, {46, 18}, {120, 40}, {176, 50}, {300, 14}})
                for (int x = 0; x + size[0] <= screen[0]; x += 9)
                    for (int y = 0; y + size[1] <= screen[1]; y += 9)
                        for (boolean show : new boolean[]{false, true}) {
                            var control = new Rect(x, y, size[0], size[1]);
                            var t = TourLayout.of(screen[0], screen[1], control, SENTENCE, false, show, show, WORDS, GuidePageTest.M);
                            String at = screen[0] + " by " + screen[1] + ", " + control;
                            assertTrue(t.bubble().inside(t.screen()), at + ": the bubble in the screen " + t.bubble());
                            assertFalse(t.bubble().overlaps(t.lit()), at + ": the bubble off the lit control " + t.bubble());
                            for (var key : t.keys()) {
                                assertTrue(key.rect().inside(t.bubble()), at + ": " + key.key() + " in the bubble");
                                assertTrue(GuidePageTest.M.width(key.text()) <= key.rect().w() - 6, at + ": " + key.text() + " whole");
                            }
                            for (int i = 0; i < t.lines().size(); i++) {
                                assertTrue(GuidePageTest.M.width(t.lines().get(i)) <= t.bubble().w() - 2 * TourLayout.INSET, at + ": a line wraps");
                                int lineBottom = t.textY() + (i + 1) * 10;
                                for (var key : t.keys()) assertTrue(lineBottom <= key.rect().y(), at + ": the text over the keys");
                            }
                            assertEquals(show, t.keys().stream().anyMatch(k -> k.key().equals("show")), at + ": Show me where the last stop offers a film");
                            cases++;
                        }
        assertTrue(cases > 3000, "swept: " + cases);
    }

    @Test
    void theDimTilesTheScreenLessTheLitRectangle() {
        for (var screen : new int[][]{{464, 254}, {320, 240}})
            for (var control : new Rect[]{new Rect(0, 0, 18, 18), new Rect(100, 50, 46, 18), new Rect(screen[0] - 30, screen[1] - 20, 30, 20), new Rect(8, 56, screen[0] - 16, 14)}) {
                var t = TourLayout.of(screen[0], screen[1], control, SENTENCE, true, false, false, WORDS, GuidePageTest.M);
                long area = 0;
                for (var r : t.dim()) {
                    assertTrue(r.inside(t.screen()), "a dim rectangle inside the screen");
                    assertFalse(r.overlaps(t.lit()), "a dim rectangle off the lit one");
                    area += (long) r.w() * r.h();
                    for (var other : t.dim()) if (other != r) assertFalse(r.overlaps(other), "the dim rectangles do not overlap");
                }
                assertEquals((long) screen[0] * screen[1] - (long) t.lit().w() * t.lit().h(), area, "they cover the rest whole");
                var grown = new Rect(control.x() - TourLayout.PAD, control.y() - TourLayout.PAD, control.w() + 2 * TourLayout.PAD, control.h() + 2 * TourLayout.PAD);
                assertTrue(t.lit().inside(grown) && t.lit().inside(t.screen()), "the lit rectangle is the control and its pad, within the screen");
                assertTrue(t.keys().stream().noneMatch(k -> k.key().equals("back")), "no Back at the first stop");
            }
    }
}

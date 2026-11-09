package com.wormzjl.icheme.client.gui.common.kit;

import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A batch holds its texts and sprites so that its fills go out in runs (P5, 2026-11-04), and nothing changes what lies on top
 * of what: painted in the order the batch sends them out, every pixel is the one the order of the calls paints. The draw
 * calls are counted as 1.21.1 {@code GuiGraphics} makes them.
 */
class HeldDrawsTest {
    private static final int W = 64, H = 48;

    private static void paint(int[] raster, int x0, int y0, int x1, int y1, int id) {
        for (int y = Math.max(0, y0); y < Math.min(H, y1); y++)
            for (int x = Math.max(0, x0); x < Math.min(W, x1); x++) raster[y * W + x] = id;
    }

    @Test
    void whatLiesOnTopStaysOnTop() {
        var random = new Random(20261104);
        for (int trial = 0; trial < 2000; trial++) {
            int[] called = new int[W * H], held = new int[W * H];
            var draws = new HeldDraws();
            boolean open = false;
            int n = 5 + random.nextInt(60);
            for (int id = 1; id <= n; id++) {
                int x0 = random.nextInt(W), y0 = random.nextInt(H), x1 = x0 + 1 + random.nextInt(20), y1 = y0 + 1 + random.nextInt(12);
                int kind = random.nextInt(10);
                if (kind == 0) {
                    // A batch opens or ends, or a clip changes, between draws.
                    if (!open) draws.begin(() -> {
                    });
                    else draws.end();
                    open = !open;
                    continue;
                }
                if (kind == 1) {
                    draws.clip();
                    continue;
                }
                int[] box = {x0, y0, x1, y1};
                int which = id;
                paint(called, x0, y0, x1, y1, id);
                if (kind < 6) {
                    draws.fill(x0, y0, x1, y1);
                    paint(held, x0, y0, x1, y1, id);
                } else if (kind < 9) draws.text(x0, y0, x1, y1, () -> paint(held, box[0], box[1], box[2], box[3], which));
                else draws.sprite(x0, y0, x1, y1, () -> paint(held, box[0], box[1], box[2], box[3], which));
            }
            if (open) draws.end();
            assertArrayEquals(called, held, "trial " + trial + ": a pixel lies under another than the calls put it");
        }
    }

    @Test
    void outsideABatchEachDrawIsOne() {
        var draws = new HeldDraws();
        draws.fill(0, 0, 4, 4);
        draws.fill(8, 0, 12, 4);
        draws.text(0, 10, 20, 19, () -> {
        });
        draws.sprite(30, 0, 40, 10, () -> {
        });
        assertEquals(4, draws.draws());
    }

    @Test
    void textsBesideTheFillsNoLongerBreakTheirRun() {
        var draws = new HeldDraws();
        var order = new StringBuilder();
        draws.begin(() -> order.append("|"));
        for (int i = 0; i < 10; i++) {
            draws.fill(0, i * 20, 30, i * 20 + 10);
            int k = i;
            draws.text(40, i * 20, 60, i * 20 + 9, () -> order.append(k));
        }
        draws.sprite(100, 0, 110, 10, () -> order.append("s"));
        draws.end();
        assertEquals("0123456789|s", order.toString(), "texts then the buffer, then the sprite");
        assertEquals(3, draws.draws(), "one run of fills, one of texts, the sprite: once 21");
    }

    @Test
    void aFillOverAWaitingTextSendsItFirst() {
        var draws = new HeldDraws();
        var order = new StringBuilder();
        draws.begin(() -> {
        });
        draws.fill(0, 0, 50, 20);
        draws.text(5, 5, 30, 14, () -> order.append("a"));
        draws.fill(20, 8, 40, 30);
        order.append("F");
        draws.end();
        assertEquals("aF", order.toString());
        assertEquals(3, draws.draws(), "the fills under the text, the text, the fill over it");
    }

    @Test
    void aTextOverAWaitingSpriteSendsItFirstButASpriteOverATextWaits() {
        var draws = new HeldDraws();
        var order = new StringBuilder();
        draws.begin(() -> order.append("|"));
        draws.text(0, 0, 20, 9, () -> order.append("a"));
        draws.sprite(10, 0, 30, 10, () -> order.append("s"));
        draws.text(25, 2, 45, 11, () -> order.append("b"));
        draws.end();
        assertEquals("a|sb", order.toString(), "the sprite over a stays over it, b over the sprite goes after it");
        assertEquals(3, draws.draws(), "a, the sprite, b");
    }
}

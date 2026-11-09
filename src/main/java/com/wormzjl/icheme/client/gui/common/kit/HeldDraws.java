package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * What a batch ({@link Canvas#batch}) holds back so that its fills go out in one run, and the draw calls that makes, counted
 * as 1.21.1 {@code GuiGraphics} makes them (the owner, 2026-11-03: "A: batch the loops"; batch 2026-11-02-process-simulator,
 * PERFORMANCE_REVIEW.md proposal 5, extended to the texts and sprites that broke the fill runs).
 * <p>
 * In a batch a fill goes out as it comes, and texts and sprites wait, each with its box in screen pixels. A fill over a waiting
 * text or sprite, and a text over a waiting sprite, first send out what waits; so does a clip and the batch's end. What waits
 * then never lies under anything that came after it, so drawing the fills, then the waiting texts, then the waiting sprites
 * lays every pixel as the order of the calls would: nothing changes what lies on top of what. Texts after texts and sprites
 * after sprites keep their order.
 * <p>
 * The game's draw calls: outside a batch every fill, text and sprite is one. In a batch the buffer holds one render type at a
 * time: a run of fills is one, the texts sent out together are one, and each sprite is one (a blit draws at once, after the
 * buffer). They are counted by kind, for they weigh differently: a text's binds the text shader, the font atlas and the
 * lightmap where a fill's binds the colour shader, so a page of interleaved texts and fills costs more than its count of
 * fill runs alone (FRAME_TIME_REVIEW.md, the Results page).
 */
public final class HeldDraws {
    /** A waiting text's box grows by this much on every side: a glyph's quad may reach a pixel past its advance and line. */
    public static final float GLYPH_MARGIN = 1;
    private static final int BAND = 16;

    private final ArrayList<Runnable> texts = new ArrayList<>(), sprites = new ArrayList<>();
    /** The waiting draws' boxes, four floats each, and whether each is a sprite. */
    private float[] boxes = new float[64];
    private boolean[] sprite = new boolean[16];
    private int held;
    /** The waiting draws by band of {@value #BAND} screen pixels in y: a fill looks only at those in its bands. */
    private int[][] bands = new int[8][];
    private int[] inBand = new int[8];
    private int depth, fillDraws, textDraws, spriteDraws;
    /** A run of fills, or of texts, sits in the buffer and ends as one draw call. */
    private boolean fills, textRun;
    private Runnable endBuffer = () -> {
    };

    /** Whether a batch is open. */
    public boolean open() {
        return depth > 0;
    }

    /** Opens a batch; {@code endBuffer} draws what the buffer holds (the game's {@code flush}), which a sprite needs first. True for the outermost. */
    public boolean begin(Runnable endBuffer) {
        if (depth++ > 0) return false;
        this.endBuffer = endBuffer;
        return true;
    }

    /** Sends out what waits. The outermost batch's end then draws what the buffer holds. */
    public void end() {
        if (depth == 1) {
            release();
            close();
        }
        depth = Math.max(0, depth - 1);
    }

    /** The buffer's run ends: a clip changes, or the batch ends. */
    private void close() {
        if (fills) fillDraws++;
        if (textRun) textDraws++;
        fills = textRun = false;
    }

    /** A clip is about to change: what waits goes out under the clip it was drawn in, and the buffer draws. */
    public void clip() {
        if (depth == 0) return;
        release();
        close();
    }

    /** A fill at these screen pixels, which the caller then draws: what waits under it goes out first. */
    public void fill(float x0, float y0, float x1, float y1) {
        if (depth == 0) {
            fillDraws++;
            return;
        }
        if (held > 0 && covers(x0, y0, x1, y1, false)) release();
        if (textRun) {
            textDraws++;
            textRun = false;
        }
        fills = true;
    }

    /** A text whose glyphs lie in this box, drawn by {@code draw} now or once the batch sends it out. */
    public void text(float x0, float y0, float x1, float y1, Runnable draw) {
        if (depth == 0) {
            textDraws++;
            draw.run();
            return;
        }
        x0 -= GLYPH_MARGIN;
        y0 -= GLYPH_MARGIN;
        x1 += GLYPH_MARGIN;
        y1 += GLYPH_MARGIN;
        if (held > 0 && covers(x0, y0, x1, y1, true)) release();
        texts.add(draw);
        hold(x0, y0, x1, y1, false);
    }

    /** A sprite stretched over this box, drawn by {@code draw} now or once the batch sends it out (after the texts, which it lies over). */
    public void sprite(float x0, float y0, float x1, float y1, Runnable draw) {
        if (depth == 0) {
            spriteDraws++;
            draw.run();
            return;
        }
        sprites.add(draw);
        hold(x0, y0, x1, y1, true);
    }

    /** The draw calls made so far. */
    public int draws() {
        return fillDraws + textDraws + spriteDraws;
    }

    /** Of {@link #draws}: the runs of fills (a fill alone outside a batch is one), of texts (a text alone is one), and the sprites. */
    public int fillDraws() {
        return fillDraws;
    }

    public int textDraws() {
        return textDraws;
    }

    public int spriteDraws() {
        return spriteDraws;
    }

    private void hold(float x0, float y0, float x1, float y1, boolean isSprite) {
        if (held * 4 + 4 > boxes.length) boxes = Arrays.copyOf(boxes, boxes.length * 2);
        if (held >= sprite.length) sprite = Arrays.copyOf(sprite, sprite.length * 2);
        boxes[held * 4] = x0;
        boxes[held * 4 + 1] = y0;
        boxes[held * 4 + 2] = x1;
        boxes[held * 4 + 3] = y1;
        sprite[held] = isSprite;
        for (int b = band(y0), last = band(y1); b <= last; b++) {
            if (b >= bands.length) {
                bands = Arrays.copyOf(bands, Math.max(b + 1, bands.length * 2));
                inBand = Arrays.copyOf(inBand, bands.length);
            }
            if (bands[b] == null) bands[b] = new int[8];
            else if (inBand[b] == bands[b].length) bands[b] = Arrays.copyOf(bands[b], bands[b].length * 2);
            bands[b][inBand[b]++] = held;
        }
        held++;
    }

    private static int band(float y) {
        return Math.max(0, (int) Math.floor(y / BAND));
    }

    /** Whether the box overlaps a waiting draw: any of them, or only sprites. */
    private boolean covers(float x0, float y0, float x1, float y1, boolean spritesOnly) {
        if (x1 <= x0 || y1 <= y0) return false;
        for (int b = band(y0), last = Math.min(band(y1), bands.length - 1); b <= last; b++)
            for (int k = 0; k < inBand[b]; k++) {
                int i = bands[b][k];
                if (spritesOnly && !sprite[i]) continue;
                if (x0 < boxes[i * 4 + 2] && boxes[i * 4] < x1 && y0 < boxes[i * 4 + 3] && boxes[i * 4 + 1] < y1) return true;
            }
        return false;
    }

    /** Sends out what waits: the texts into the buffer behind its fills, then the buffer, then each sprite. Before a draw past the canvas, too. */
    public void release() {
        if (held == 0) return;
        if (!texts.isEmpty()) {
            if (fills) {
                fillDraws++;
                fills = false;
            }
            for (var text : texts) text.run();
            textRun = true;
        }
        if (!sprites.isEmpty()) {
            close();
            endBuffer.run();
            for (var s : sprites) {
                s.run();
                spriteDraws++;
            }
        }
        texts.clear();
        sprites.clear();
        held = 0;
        Arrays.fill(inBand, 0);
    }
}

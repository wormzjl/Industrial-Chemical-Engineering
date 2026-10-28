package com.wormzjl.icheme.client.gui.common.kit;

/**
 * Where the kit and the flowsheets draw, in GUI pixels: the game's {@code GuiGraphics} ({@link GuiCanvas}), or an image
 * in the render tool. Fills, lines and outlines follow {@code GuiGraphics}: a fill covers x0 to x1 and y0 to y1
 * exclusive; a horizontal line includes both ends, a vertical one neither.
 */
public interface Canvas {
    Measure measure();

    void fill(int x0, int y0, int x1, int y1, int argb);

    default void hLine(int x0, int x1, int y, int argb) {
        fill(Math.min(x0, x1), y, Math.max(x0, x1) + 1, y + 1, argb);
    }

    default void vLine(int x, int y0, int y1, int argb) {
        fill(x, Math.min(y0, y1) + 1, x + 1, Math.max(y0, y1), argb);
    }

    default void outline(int x, int y, int w, int h, int argb) {
        fill(x, y, x + w, y + 1, argb);
        fill(x, y + h - 1, x + w, y + h, argb);
        fill(x, y + 1, x + 1, y + h - 1, argb);
        fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
    }

    /** {@code text} with its top left at ({@code x}, {@code y}), no shadow. */
    void text(String text, int x, int y, int argb);

    /** {@code text} at ({@code x}, {@code y}) in the font scaled by {@code scale}. */
    void text(String text, float x, float y, float scale, int argb);

    /** Draws only inside {@code area} until {@link #unclip}. */
    void clip(Rect area);

    void unclip();
}

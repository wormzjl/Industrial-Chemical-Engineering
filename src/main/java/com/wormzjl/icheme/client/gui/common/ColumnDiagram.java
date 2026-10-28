package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;

/**
 * The column schematic shared by the column calculator and the equipment constructor (batch
 * 2026-10-15-custom-equipment-rendering): a shell 49 pixels wide with rounded heads, a line per tray, and the feed, side
 * draws, pumparounds and steam feeds marked on its sides. Each screen maps trays to heights and adds its own labels and
 * click targets. It draws on the kit's {@link Canvas} (batch 2026-10-28-gui-toolkit), so the render tool draws it too.
 */
public final class ColumnDiagram {
    /** Half the shell's width; marks start {@link #MARK} pixels from the axis. */
    public static final int HALF = 24, MARK = 26;

    private ColumnDiagram() {
    }

    /** The shell from {@code top} to {@code bottom} around axis {@code x}: filled, with its heads and sides outlined. */
    public static void shell(Canvas g, int x, int top, int bottom, int fill, int line) {
        g.fill(x - HALF, top + 10, x + HALF + 1, bottom - 10, fill);
        for (int dy = 0; dy < 10; dy++) {
            int inset = 10 - (int) Math.sqrt(100 - (10 - dy) * (10 - dy));
            g.hLine(x - HALF + inset, x + HALF - inset, top + dy, line);
            g.hLine(x - HALF + inset, x + HALF - inset, bottom - dy, line);
        }
        g.vLine(x - HALF - 1, top + 10, bottom - 10, line);
        g.vLine(x + HALF + 1, top + 10, bottom - 10, line);
    }

    public static void tray(Canvas g, int x, int y, int color) {
        g.hLine(x - 20, x + 20, y, color);
    }

    /** The feed line from {@code from} to the shell's left side. */
    public static void feed(Canvas g, int x, int from, int y, int color) {
        g.hLine(from, x - MARK, y, color);
    }

    /** A side draw leaving the shell's right side. */
    public static void draw(Canvas g, int x, int y, int color) {
        g.hLine(x + MARK, x + MARK + 13, y, color);
    }

    /** Pumparound {@code index}: its lane left of the shell joining the draw tray at {@code a} and the return tray at {@code b}; returns the lane. */
    public static int pumparound(Canvas g, int x, int index, int a, int b, int color) {
        int lane = x - 34 - index * 7;
        g.vLine(lane, Math.min(a, b), Math.max(a, b), color);
        g.hLine(lane, x - MARK, a, color);
        g.hLine(lane, x - MARK, b, color);
        return lane;
    }

    /** Steam feed {@code index}: a dashed line into the shell's left side; returns where it starts. */
    public static int steam(Canvas g, int x, int index, int y, int color) {
        int start = x - 82 - index * 8;
        for (int dx = start; dx < x - MARK; dx += 5) g.hLine(dx, Math.min(dx + 2, x - MARK), y, color);
        return start;
    }
}

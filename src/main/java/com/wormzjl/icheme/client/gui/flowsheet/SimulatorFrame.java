package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The simulator's frame as rectangles (batch 2026-11-02-process-simulator, sheets 01 and 05): title, tabs and file keys, the one status display, the canvas's
 * well, the palette and the footer, at the approved numbers, for a window of {@code w} by {@code h}. The screen hit-tests and the render tool draws the same slots.
 */
public final class SimulatorFrame {
    public enum Size {
        WIDE, COMPACT, TINY;

        public static Size of(int w) {
            return w >= 760 ? WIDE : w >= 500 ? COMPACT : TINY;
        }

        boolean narrow() {
            return this != WIDE;
        }
    }

    /** The screen's tabs. */
    public enum Tab {
        FLOWSHEET, RESULTS, SIZING, BASIS
    }

    public static final int BOTTOM = 38, PANEL = 182, RESULT_DOOR = 168, BASIS_DOOR = 200, SIZING_DOOR = 180, DOOR_TOP = 74, CATEGORY_H = 18;
    /** The design minimum and the size a door takes. */
    public static final int LEAST_W = 464, LEAST_H = 254, DOOR_W = 790, DOOR_H = 524;

    private final int w, h;
    private final Size size;

    public SimulatorFrame(int w, int h) {
        this.w = w;
        this.h = h;
        size = Size.of(w);
    }

    public Size size() {
        return size;
    }

    /** The theme key on the title row, where every door has it. */
    public Rect theme() {
        return CalculatorColumn.theme(w);
    }

    public List<Rect> tabs() {
        int tw = size.narrow() ? 56 : 84;
        var tabs = new ArrayList<Rect>();
        for (int i = 0; i < Tab.values().length; i++) tabs.add(new Rect(10 + i * (tw + 4), 29, tw, 18));
        return tabs;
    }

    public List<Rect> fileKeys() {
        int kw = size.narrow() ? 36 : 48, n = 4, x = w - 10 - n * kw - (n - 1) * 4;
        var keys = new ArrayList<Rect>();
        for (int i = 0; i < n; i++) keys.add(new Rect(x + i * (kw + 4), 29, kw, 18));
        return keys;
    }

    public Rect status() {
        return new Rect(10, 59, w - 20, 9);
    }

    public int bottom() {
        return h - BOTTOM;
    }

    public int paletteHeight() {
        return size == Size.TINY ? 40 : size == Size.COMPACT ? 58 : 80;
    }

    /** The canvas's well, before the skin's edge. */
    public Rect well() {
        return new Rect(5, 71, w - 10, bottom() - paletteHeight() - 4 - 71);
    }

    public Rect palette() {
        return new Rect(5, bottom() - paletteHeight(), w - 10, paletteHeight());
    }

    /** The box inside a well, as {@code PanelSkin.well} returns it, for a slot that must stand before the first frame draws. A gate holds the two together. */
    public static Rect inner(Rect well) {
        return new Rect(well.x() + 6, well.y() + 6, well.w() - 12, well.h() - 13);
    }

    /** The panel over the canvas's {@code box}. */
    public static Rect panel(Rect box) {
        return new Rect(box.right() + 3 - PANEL, box.y() - 3, PANEL, box.h() + 6);
    }

    /** The zoom keys in their tray at the canvas's top left. */
    public static List<Rect> zoomKeys(Rect box) {
        int kw = 26, x = box.x() + 5;
        var keys = new ArrayList<Rect>();
        for (int i = 0; i < 3; i++) keys.add(new Rect(x + i * (kw + 4), box.y() + 5, kw, 15));
        return keys;
    }

    public static Rect zoomTray(Rect box) {
        return new Rect(box.x() + 2, box.y() + 2, 3 * 26 + 8 + 6, 21);
    }

    /**
     * The footer's keys: Close, the tab's two keys, the second {@code third} px wide, then Undo and Redo where they fit. The
     * Flowsheet tabs' are Run and Stop (50); the Sizing tab's Size all and Send to block (84, 50 where the window is narrow).
     */
    public List<Rect> footer(int third) {
        int y = h - 27, x = 10;
        var keys = new ArrayList<Rect>();
        keys.add(new Rect(x, y, 50, 18));
        x += 54;
        keys.add(new Rect(x, y, 60, 18));
        x += 64;
        keys.add(new Rect(x, y, third, 18));
        x += third + 4;
        if (size != Size.TINY) for (int i = 0; i < 2; i++) {
            keys.add(new Rect(x, y, 50, 18));
            x += 54;
        }
        return keys;
    }

    public List<Rect> softKeys() {
        int kw = 40, strip = 4 * kw + 18, kx = w - 10 - strip;
        var keys = new ArrayList<Rect>();
        for (int i = 0; i < 4; i++) keys.add(new Rect(kx + i * (kw + 6), h - 27, kw, 18));
        return keys;
    }

    public Rect softTray() {
        int strip = 4 * 40 + 18;
        return new Rect(w - 10 - strip - 4, h - 31, strip + 8, 26);
    }

    // ---- The palette ----

    /** A tile as placed. */
    public record TileSlot(Tiles.Tile tile, Rect rect, boolean captions) {
    }

    /** The category keys: one row, or a tiny window's one key and its steppers. */
    public List<Rect> categories() {
        var r = palette();
        var keys = new ArrayList<Rect>();
        if (size == Size.TINY) {
            keys.add(new Rect(r.x() + 5, r.y() + 3, 54, 18));
            keys.add(new Rect(r.x() + 5, r.y() + 22, 26, 15));
            keys.add(new Rect(r.x() + 33, r.y() + 22, 26, 15));
            return keys;
        }
        int gap = 3, n = Tiles.CATEGORIES.size(), kw = (r.w() - 10 - (n - 1) * gap) / n;
        for (int i = 0; i < n; i++) keys.add(new Rect(r.x() + 5 + i * (kw + gap), r.y(), kw, CATEGORY_H));
        return keys;
    }

    /** The palette's well. */
    public Rect tileWell() {
        var r = palette();
        return size == Size.TINY ? new Rect(r.x() + 62, r.y(), r.w() - 62, r.h()) : new Rect(r.x(), r.y() + 20, r.w(), r.h() - 20);
    }

    /** The tiles of category {@code selected} that fit, in {@code box}, the well's inner box. */
    public List<TileSlot> tiles(int selected, Rect box) {
        var tiles = Tiles.CATEGORIES.get(selected).tiles();
        var slots = new ArrayList<TileSlot>();
        boolean tiny = size == Size.TINY, compact = size.narrow();
        int tw = tiny ? 42 : compact ? 48 : 66, pitch = tw + 2;
        for (int i = 0; i < tiles.size() && (i + 1) * pitch <= box.w(); i++)
            slots.add(new TileSlot(tiles.get(i), new Rect(box.x() + (tiny ? 0 : 2) + i * pitch, box.y(), tw, box.h()), !compact));
        return slots;
    }

    /** The box a tile gives its symbol: its natural shape, a column's squatter, fitted to {@code roomW} by {@code room}. */
    public static int[] tileSize(Symbol symbol, int roomW, int room) {
        boolean column = symbol == Symbol.COLUMN_TOP;
        int gw = column ? 16 : symbol.w, gh = column ? 44 : symbol.h, feet = symbol == Symbol.LYING ? 7 : 0;
        double s = Math.min(1, Math.min(roomW / (double) gw, room / (double) (gh + feet)));
        return new int[]{(int) Math.round(gw * s), (int) Math.round(gh * s)};
    }

    /** The tile under a point, or null. */
    public @Nullable Tiles.Tile tileAt(int selected, Rect box, double x, double y) {
        for (var slot : tiles(selected, box)) if (slot.rect().contains(x, y)) return slot.tile();
        return null;
    }

    /** Rows placed in a door column of the Results and Basis tabs. */
    public static Column doorColumn(List<? extends Column.Row> rows, int width, com.wormzjl.icheme.client.gui.common.kit.Measure m) {
        return Column.place(rows, 10, DOOR_TOP, width - 10, m, Column.Metrics.COMPACT);
    }
}

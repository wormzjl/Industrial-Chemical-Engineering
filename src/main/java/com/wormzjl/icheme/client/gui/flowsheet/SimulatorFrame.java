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

    private final int w, h, title;
    private final Size size;

    public SimulatorFrame(int w, int h) {
        this(w, h, 0);
    }

    /** @param title the title's text width, px: the keys on the title row stay clear of its plate */
    public SimulatorFrame(int w, int h, int title) {
        this.w = w;
        this.h = h;
        this.title = title;
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
        int tw = tabWidth(wideKeys());
        var tabs = new ArrayList<Rect>();
        for (int i = 0; i < Tab.values().length; i++) tabs.add(new Rect(10 + i * (tw + 4), 29, tw, 18));
        return tabs;
    }

    public List<Rect> fileKeys() {
        int kw = fileWidth(wideKeys()), n = 4, x = filesLeft(wideKeys());
        var keys = new ArrayList<Rect>();
        for (int i = 0; i < n; i++) keys.add(new Rect(x + i * (kw + 4), 29, kw, 18));
        return keys;
    }

    /**
     * Whether the tabs and file keys take their wide widths: only on a wide window where the tab's key groups still fit
     * between them; elsewhere the narrow ones, as a compact window's, and Save as reads short.
     */
    public boolean wideKeys() {
        return size == Size.WIDE && filesLeft(true) - tabsRight(true) >= need();
    }

    /** Whether the tab's key groups stand on the tab row, between the tabs and the file keys; elsewhere on the title row. */
    public boolean keysOnTabRow() {
        boolean wide = wideKeys();
        return filesLeft(wide) - tabsRight(wide) >= need();
    }

    private static int tabWidth(boolean wide) {
        return wide ? 84 : 56;
    }

    private static int fileWidth(boolean wide) {
        return wide ? 48 : 36;
    }

    private static int tabsRight(boolean wide) {
        return 10 + 4 * tabWidth(wide) + 3 * 4;
    }

    private int filesLeft(boolean wide) {
        return w - 10 - 4 * fileWidth(wide) - 3 * 4;
    }

    /** The key groups' width with {@code third} the tab's second key: Run (60) and Stop, then Undo and Redo (50 each). */
    private static int groups(int third) {
        return 60 + 4 + third + PAIR_GAP + 50 + 4 + 50;
    }

    /**
     * The room the groups need between the tabs and the file keys: their recesses, the neighbours' and 4 px between each.
     * Measured with the widest second key the window shows (Size all's), so no tab moves the row.
     */
    private int need() {
        return groups(third(Tab.SIZING)) + 4 * RECESS + 2 * 4;
    }

    /** The tab's second key's width: Size all's 84 on a wide window; Run's, Stop's and a narrow Size all's 50. */
    public int third(Tab tab) {
        return tab == Tab.SIZING && size == Size.WIDE ? 84 : 50;
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
     * A tab's keys: Close in the footer, then the tab's two keys, the second {@code third} px wide ({@link #third}), and Undo and
     * Redo, in two groups (the owner, 2026-11-04: "should be in the middle of these 2, regroup these buttons"): on the tab row,
     * centred between the tabs and the file keys, where they fit ({@link #keysOnTabRow}); else centred on the title row, never
     * over the title's plate. The Flowsheet tab's are Run and Stop; the Sizing tab's Size all and Send to block.
     */
    public List<Rect> keys(int third) {
        var keys = new ArrayList<Rect>();
        keys.add(new Rect(10, h - 27, 50, 18));
        int[] widths = {60, third, 50, 50};
        boolean tabRow = keysOnTabRow(), wide = wideKeys();
        int y = tabRow ? 29 : 5;
        int x = tabRow ? tabsRight(wide) + (filesLeft(wide) - tabsRight(wide) - groups(third)) / 2 : Math.max((w - groups(third)) / 2, TITLE_RIGHT + title + 6 + RECESS);
        for (int i = 0; i < widths.length; i++) {
            keys.add(new Rect(x, y, widths[i], 18));
            x += widths[i] + (i == 1 ? PAIR_GAP : 4);
        }
        return keys;
    }

    /**
     * The recesses pressed into the door under the top rows' key groups (the owner, 2026-11-04: "Need to change style of other
     * button layout as well so they look homogeneous", the footer kept as it was): the tabs, the tab's two pairs, the file keys
     * and the theme key, {@link #RECESS} px round each.
     */
    public List<Rect> recesses(Tab tab) {
        var keys = keys(third(tab));
        return List.of(around(tabs()), around(keys.subList(1, 3)), around(keys.subList(3, 5)), around(fileKeys()), around(List.of(theme())));
    }

    private static Rect around(List<Rect> keys) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (var r : keys) {
            x0 = Math.min(x0, r.x());
            y0 = Math.min(y0, r.y());
            x1 = Math.max(x1, r.right());
            y1 = Math.max(y1, r.bottom());
        }
        return new Rect(x0 - RECESS, y0 - RECESS, x1 - x0 + 2 * RECESS, y1 - y0 + 2 * RECESS);
    }

    /** Where the title's plate ends, less its text: {@code PanelSkin.title} at x 10 pads it by 4. */
    static final int TITLE_RIGHT = 14;
    /** A recess's margin round its keys; the gap between the tab's two pairs: both recesses and 6 px between them. */
    static final int RECESS = 3, PAIR_GAP = 2 * RECESS + 6;

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

    /**
     * The P, T key: a push button on its own beside the display keys' tray (the owner, 2026-11-04: "Individual button on the
     * bottom beside mole"), latched while the canvas shows each stream's T and P; its recess 6 px from the tray.
     */
    public Rect conditionsKey() {
        return new Rect(softTray().x() - 6 - RECESS - 40, h - 27, 40, 18);
    }

    public Rect conditionsRecess() {
        return around(List.of(conditionsKey()));
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

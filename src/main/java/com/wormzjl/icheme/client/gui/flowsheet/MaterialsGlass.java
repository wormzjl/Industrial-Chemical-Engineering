package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.flowsheet.metallurgy.SheetMetallurgy;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

import static com.wormzjl.icheme.client.gui.flowsheet.MaterialsPage.*;

/**
 * The Materials tab's glass (batch 2026-11-16-fouling-corrosion, WP6): the unit table (tag, material built, cost index, service
 * temperature, governing mechanism, rate and life, its lamp at the material built), and under it the picked unit's four materials side
 * by side, so the player sees why Auto chose. A click picks a row; the wheel scrolls the table. One fill a lamp and one a band, as the
 * Sizing glass draws. Minecraft-free.
 */
public final class MaterialsGlass {
    private static final int ROW = 12, GAP = 8, LAMP = 12, LAMP_SIZE = 5;

    private MaterialsGlass() {
    }

    /** One table row: its key for a click, its lamp, its cells (the last {@code right} right-aligned), whether it is picked. */
    record Item(@Nullable String key, @Nullable Lamp lamp, List<String> cells, boolean picked) {
    }

    /**
     * Columns at their cells' widths, one line a row where every column fits the glass, else as many as it takes, each column whole on
     * its line and the later lines indented under the first (the GUI skill's stacking rule, as the Sizing glass's: no cut cell).
     */
    record Table(int[] at, int[] line, int[] widths, int lines) {
        private static final int INDENT = 16;

        static Table of(Measure m, int width, String[] head, List<Item> items) {
            int n = head.length;
            int[] widths = new int[n], at = new int[n], line = new int[n];
            for (int i = 0; i < n; i++) widths[i] = m.width(head[i]);
            for (var item : items) for (int i = 0; i < n && i < item.cells().size(); i++) widths[i] = Math.max(widths[i], m.width(item.cells().get(i)));
            int x = LAMP, row = 0;
            for (int i = 0; i < n; i++) {
                if (i > 0 && x + widths[i] > width) {
                    row++;
                    x = LAMP + INDENT;
                }
                at[i] = x;
                line[i] = row;
                x += widths[i] + GAP;
            }
            return new Table(at, line, widths, row + 1);
        }

        int height() {
            return lines * ROW;
        }

        /** Draws the head and the rows from {@code first} down to {@code bottom}; the last {@code right} columns right-aligned. */
        int draw(Canvas c, Rect box, String[] head, List<Item> items, int first, int right, int y, int bottom) {
            int x0 = box.x() + 4;
            for (int i = 0; i < head.length; i++) cell(c, head[i], i, i >= head.length - right, x0, y, Palette.MUTED);
            y += height();
            c.hLine(box.x() + 2, box.right() - 2, y - 2, Palette.LINE);
            for (int r = first; r < items.size() && y + height() <= bottom; r++, y += height()) {
                var item = items.get(r);
                if ((r - first) % 2 == 1) c.fill(box.x(), y - 2, box.right(), y + height() - 2, DialogColumns.BAND);
                if (item.picked()) c.outline(box.x() + 1, y - 2, box.w() - 2, height(), Palette.ACCENT);
                if (item.lamp() != null) c.fill(x0, y + 2, x0 + LAMP_SIZE, y + 2 + LAMP_SIZE, SizingPage.colour(item.lamp()));
                for (int i = 0; i < item.cells().size(); i++)
                    cell(c, item.cells().get(i), i, i >= head.length - right, x0, y, i == 0 ? Palette.TEXT : i == 1 ? Palette.ACCENT : Palette.MUTED);
            }
            return y;
        }

        private void cell(Canvas c, String text, int i, boolean right, int x0, int y, int colour) {
            c.text(text, x0 + at[i] + (right ? widths[i] - c.measure().width(text) : 0), y + line[i] * ROW, colour);
        }
    }

    static String[] unitHead(SheetContext x) {
        return new String[]{x.text("sizing.tag"), x.text("materials.material"), x.text("materials.cost"), x.text("materials.temperature_head", x.units().temperature().symbol()),
                x.text("materials.mechanism"), x.text("materials.rate_head"), x.text("materials.life_head")};
    }

    static List<Item> unitItems(SheetContext x, State state, SheetMetallurgy.Rows rows) {
        var items = new ArrayList<Item>();
        String picked = unit(state, rows);
        for (var row : rows.rows().values()) {
            String tag = SizingPage.name(x, row.unit());
            boolean at = row.unit().equals(picked);
            if (!row.wall()) {
                var trim = row.trim();
                items.add(new Item(row.unit(), null, List.of(tag, x.text("materials.trim"), "", x.units().t(row.temperature()),
                        mechanism(x, trim.life() < Double.POSITIVE_INFINITY ? com.wormzjl.icheme.science.integrity.Mechanism.EROSION : null), "", life(x, trim.life())), at));
                continue;
            }
            var built = row.chosen(manual(x, row.unit()));
            var rated = row.rated(built);
            String name = material(x, built) + (manual(x, row.unit()) == null ? " " + x.text("materials.auto_mark") : "");
            items.add(new Item(row.unit(), rated.lamp(), List.of(tag, name, cost(built), x.units().t(row.temperature()), mechanism(x, rated.mechanism()),
                    rate(rated.rate()), life(x, rated.life())), at));
        }
        return items;
    }

    static String[] materialHead(SheetContext x, String unit) {
        return new String[]{x.text("materials.every", unit), x.text("materials.cost"), x.text("materials.mechanism"), x.text("materials.rate_head"), x.text("materials.life_head")};
    }

    /** The picked unit's four materials, Auto's marked. */
    static List<Item> materialItems(SheetContext x, SheetMetallurgy.Row row) {
        var items = new ArrayList<Item>();
        var auto = row.auto();
        var built = row.chosen(manual(x, row.unit()));
        for (var rated : row.ratings()) {
            var m = rated.material();
            String name = material(x, m) + (m == auto ? " " + x.text("materials.auto_mark") : "");
            items.add(new Item(null, rated.lamp(), List.of(name, cost(m), mechanism(x, rated.mechanism()), rate(rated.rate()), life(x, rated.life())), m == built));
        }
        return items;
    }

    /** The glass. */
    public static void draw(Canvas c, SheetContext x, State state, Rect box) {
        var rows = rows(x);
        if (rows == null || rows.rows().isEmpty()) {
            c.text(x.text(x.host().rating() ? "sizing.rating" : rows == null ? "materials.none" : "materials.empty"), box.x() + 4, box.y() + 5, Palette.MUTED);
            return;
        }
        var layout = Layout.of(x, state, rows, c.measure(), box);
        c.clip(box);
        layout.upper.draw(c, box, layout.head, layout.items, first(state, layout.items), 3, box.y() + 4, layout.top);
        if (layout.lower != null) {
            c.hLine(box.x() + 2, box.right() - 2, layout.top + 1, Palette.LINE);
            layout.lower.draw(c, box, layout.lowerHead, layout.lowerItems, 0, 2, layout.top + 6, box.bottom());
        }
        c.unclip();
    }

    private static int first(State state, List<Item> items) {
        return Math.clamp(state.offset, 0, Math.max(0, items.size() - 1));
    }

    /**
     * The glass as laid out: the unit table down to {@code top}, half the glass or higher where the picked unit's four lines need the
     * room, and those lines under it (none for a valve).
     */
    private record Layout(String[] head, List<Item> items, Table upper, int top, String @Nullable [] lowerHead, List<Item> lowerItems, @Nullable Table lower) {
        static Layout of(SheetContext x, State state, SheetMetallurgy.Rows rows, Measure m, Rect box) {
            int width = box.w() - 8;
            var head = unitHead(x);
            var items = unitItems(x, state, rows);
            String picked = unit(state, rows);
            var row = picked == null ? null : rows.rows().get(picked);
            if (row == null || !row.wall()) return new Layout(head, items, Table.of(m, width, head, items), box.bottom(), null, List.of(), null);
            var lowerHead = materialHead(x, SizingPage.name(x, picked));
            var lowerItems = materialItems(x, row);
            var lower = Table.of(m, width, lowerHead, lowerItems);
            var upper = Table.of(m, width, head, items);
            // The unit table keeps its head and one row at least; a glass too short for the four lines shows what fits of them.
            int top = Math.max(Math.max(box.y() + box.h() / 2, box.bottom() - (lowerItems.size() + 1) * lower.height() - 10), box.y() + 4 + 2 * upper.height());
            return new Layout(head, items, upper, top, lowerHead, lowerItems, lower);
        }
    }

    /** The unit under a point of the glass's unit table, or null. */
    public static @Nullable String click(SheetContext x, State state, Measure m, Rect box, double px, double py) {
        var rows = rows(x);
        if (rows == null || !box.contains(px, py)) return null;
        var layout = Layout.of(x, state, rows, m, box);
        if (py >= layout.top) return null;
        int row = (int) Math.floor((py - (box.y() + 4 + layout.upper.height() - 2)) / layout.upper.height());
        int i = first(state, layout.items) + row;
        return row >= 0 && i < layout.items.size() ? layout.items.get(i).key() : null;
    }

    /** A row clicked: the unit picked. */
    public static boolean pick(State state, @Nullable String key) {
        if (key == null) return false;
        state.unit = key;
        return true;
    }

    /** The wheel over the glass scrolls the unit table. */
    public static void scroll(State state, double dy) {
        state.offset = Math.max(0, state.offset - (int) Math.signum(dy) * 3);
    }
}

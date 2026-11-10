package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.SentDesign;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.EjectorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PressurePath;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Reading;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;
import static com.wormzjl.icheme.client.gui.flowsheet.SizingPage.*;

/**
 * The Sizing tab's glass (batch 2026-11-02-process-simulator, split from {@link SizingPage}): the item tables of the four views, the picked
 * item's limits and the path's stair, and what a click or the wheel on the glass does. Minecraft-free.
 */
public final class SizingGlass {
    private static final int ROW = 12, GAP = 8, LAMP = 12, LAMP_SIZE = 5;

    private SizingGlass() {
    }

    // ---- The glass ----

    /** One cell of a table. */
    private record Cell(String text, int colour, boolean right) {
        static Cell of(String text, int colour) {
            return new Cell(text, colour, false);
        }

        static Cell right(String text, int colour) {
            return new Cell(text, colour, true);
        }
    }

    /** One row: its key for a click, its lamp, its cells, whether it is the item picked. */
    private record Item(@Nullable String key, @Nullable Lamp lamp, List<Cell> cells, boolean picked, @Nullable String block) {
        Item(@Nullable String key, @Nullable Lamp lamp, List<Cell> cells, boolean picked) {
            this(key, lamp, cells, picked, null);
        }

        /** A row for unit or member {@code id}: a member's ({@code T-101/condenser}) hangs off its block's row on the tree. */
        static Item of(String id, @Nullable Lamp lamp, List<Cell> cells, boolean picked) {
            return new Item(id, lamp, cells, picked, SizingPage.block(id));
        }
    }

    /**
     * A table laid out on its cells' own widths: one line a row where every column fits the width, else as many as it takes, each
     * column whole on its line and the later lines indented under the first column (the GUI skill's stacking rule: no cut cell).
     */
    private record Table(int[] at, int[] line, int[] widths, int lines) {
        /** A stacked row's later lines; a member's name after its branch. */
        private static final int INDENT = 16, TREE = 10;

        static Table of(Measure m, int width, String[] head, List<Item> items) {
            int n = head.length;
            int[] widths = new int[n];
            for (int i = 0; i < n; i++) widths[i] = m.width(head[i]);
            for (var item : items)
                for (int i = 0; i < n && i < item.cells().size(); i++) widths[i] = Math.max(widths[i], m.width(item.cells().get(i).text()) + (i == 0 && item.block() != null ? TREE : 0));
            int[] at = new int[n], line = new int[n];
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

        void cell(Canvas c, Cell cell, int i, int x0, int y) {
            cell(c, cell, i, x0, y, 0);
        }

        /** A cell, its text {@code shift} px in from its column's left (a member's name, after its branch). */
        void cell(Canvas c, Cell cell, int i, int x0, int y, int shift) {
            int x = x0 + at[i] + (cell.right() ? widths[i] - c.measure().width(cell.text()) : shift);
            c.text(cell.text(), x, y + line[i] * ROW, cell.colour());
        }

        /**
         * A member's branch before its name (the owner, 2026-11-04: "Add some kind of indicator to this kind of sub-equipment so
         * player understands that they belong to the column", picked A, tree lines): a line down the name column's left from its
         * block's row, a tick into the name, and a corner on the block's last member.
         */
        void branch(Canvas c, int x0, int y, boolean last) {
            int x = x0 + at[0] + 2, mid = y + 3;
            c.fill(x, y - 2, x + 1, last ? mid + 1 : y + height() - 2, Palette.LINE);
            c.fill(x + 1, mid, x + TREE - 5, mid + 1, Palette.LINE);
        }

        /** Draws the head and the rows from {@code first} down to {@code bottom}; returns the y under the last row drawn. */
        int draw(Canvas c, Rect box, String[] head, List<Item> items, int first, int y, int bottom) {
            int x0 = box.x() + 4;
            for (int i = 0; i < head.length; i++) cell(c, new Cell(head[i], Palette.MUTED, false), i, x0, y);
            y += height();
            c.hLine(box.x() + 2, box.right() - 2, y - 2, Palette.LINE);
            for (int r = first; r < items.size() && y + height() <= bottom; r++, y += height()) {
                var item = items.get(r);
                if ((r - first) % 2 == 1) c.fill(box.x(), y - 2, box.right(), y + height() - 2, DialogColumns.BAND);
                if (item.picked()) c.outline(box.x() + 1, y - 2, box.w() - 2, height(), Palette.ACCENT);
                if (item.lamp() != null) c.fill(x0, y + 2, x0 + LAMP_SIZE, y + 2 + LAMP_SIZE, colour(item.lamp()));
                boolean member = item.block() != null;
                if (member) branch(c, x0, y, r + 1 >= items.size() || !item.block().equals(items.get(r + 1).block()));
                for (int i = 0; i < item.cells().size(); i++) cell(c, item.cells().get(i), i, x0, y, i == 0 && member ? TREE : 0);
            }
            return y;
        }
    }

    /**
     * {@code items} with a row of the block's own before members that have none above them (the Lines table lists a column's
     * lines, not the column), so every branch hangs off its block, never off the row before it. The row picks nothing.
     */
    private static List<Item> parented(SheetContext x, List<Item> items) {
        var out = new ArrayList<Item>();
        for (var item : items) {
            String block = item.block();
            if (block != null && (out.isEmpty() || !block.equals(out.getLast().block()) && !block.equals(out.getLast().key())))
                out.add(new Item(null, null, List.of(Cell.of(name(x, block), Palette.TEXT)), false));
            out.add(item);
        }
        return out;
    }

    private static String[] unitHead(SheetContext x) {
        return new String[]{x.text("sizing.tag"), x.text("sizing.equipment"), x.text("sizing.size"), x.text("sizing.closest"), x.text("sizing.value"), x.text("sizing.limit")};
    }

    private static List<Item> unitItems(SheetContext x, State state, SheetSizing.Sized sized) {
        var items = new ArrayList<Item>();
        String picked = unit(state, sized);
        for (var entry : sized.units().entrySet()) {
            String id = entry.getKey();
            var rated = entry.getValue();
            var size = size(x, id, sized);
            var closest = rated.rating().closest();
            int tone = tone(rated.rating().lamp());
            String block = block(id);
            String tag = block == null ? name(x, id) : member(x, id.substring(block.length() + 1));
            items.add(Item.of(id, rated.rating().lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.of(equipment(x, id, size), Palette.MUTED), Cell.of(sizeText(x, size), Palette.ACCENT),
                    Cell.of(closest == null ? "" : limitName(x, closest.limit()), Palette.MUTED), Cell.right(closest == null ? "" : value(x, closest), closest == null ? Palette.ACCENT : tone(closest.lamp())),
                    Cell.right(closest == null ? "" : limit(x, closest), Palette.MUTED)), id.equals(picked)));
        }
        return parented(x, items);
    }

    private static String[] lineHead(SheetContext x, SheetUnits u) {
        return new String[]{x.text("sizing.line"), x.text("sizing.service"), u.time().unit("m³"), x.text("sizing.bore_m"), "m/s", "bar/100 m", "ρv²", x.text("sizing.closest")};
    }

    private static List<Item> lineItems(SheetContext x, State state, SheetSizing.Sized sized) {
        var items = new ArrayList<Item>();
        String picked = line(state, sized);
        var u = x.units();
        for (var entry : sized.lines().entrySet()) {
            String id = entry.getKey();
            var rated = entry.getValue();
            var line = rated.line();
            var service = facts(x).line(id).service() != null ? facts(x).line(id).service() : line.service();
            var rating = rated.rating();
            var closest = rating.closest();
            String block = block(id);
            String tag = block == null ? name(x, id) : member(x, id.substring(block.length() + 1));
            items.add(Item.of(id, rating.lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.of(service == null ? "" : x.text("sizing.service." + service.name().toLowerCase(Locale.ROOT)), Palette.MUTED),
                    Cell.right(line.flows() ? number(u.time().display(line.volume())) : "", Palette.ACCENT), Cell.right(lineSizeText(x, lineSize(x, id, sized)), Palette.ACCENT),
                    reading(rating, service == null ? null : service.speed()), reading(rating, service == null ? null : service.drop()), reading(rating, service == null ? null : service.momentum()),
                    Cell.of(closest == null ? "" : limitName(x, closest.limit()), Palette.MUTED)), id.equals(picked)));
        }
        return parented(x, items);
    }

    private static Cell reading(Rating rating, @Nullable Limit limit) {
        var r = limit == null ? null : rating.reading(limit);
        return Cell.right(r == null ? "" : number(r.value()), r == null ? Palette.ACCENT : tone(r.lamp()));
    }

    private static String[] limitHead(SheetContext x, String title) {
        return new String[]{title, x.text("sizing.kind"), x.text("sizing.value"), x.text("sizing.limit"), x.text("sizing.from")};
    }

    private static List<Item> limitItems(SheetContext x, Rating rating) {
        var items = new ArrayList<Item>();
        for (var r : rating.readings())
            items.add(new Item(null, r.lamp(), List.of(Cell.of(limitName(x, r.limit()), Palette.TEXT), Cell.of(x.text(r.limit().kind() == Limit.Kind.MUST ? "sizing.must" : "sizing.should"), Palette.MUTED),
                    Cell.right(value(x, r), tone(r.lamp())), Cell.right(limit(x, r), Palette.MUTED), Cell.of(source(x, r.limit()), Palette.MUTED)), false));
        return items;
    }

    private static String[] pathHead(SheetContext x) {
        return new String[]{x.text("sizing.from"), x.text("sizing.to"), x.text("sizing.available"), x.text("sizing.lost"), x.text("sizing.spare")};
    }

    private static List<Item> pathItems(SheetContext x, State state, SheetSizing.Sized sized) {
        var items = new ArrayList<Item>();
        var picked = path(state, sized);
        for (int i = 0; i < sized.paths().size(); i++) {
            var path = sized.paths().get(i);
            var lamp = path.rating().lamp();
            items.add(new Item("path/" + i, lamp, List.of(Cell.of(name(x, path.start()), Palette.TEXT), Cell.of(name(x, path.end()) + (path.taker() == null || path.taker().equals(path.end()) ? "" : " (" + name(x, path.taker()) + ")"), Palette.TEXT),
                    Cell.right(SheetUnits.number(2, path.available()) + " bar", Palette.ACCENT), Cell.right(SheetUnits.number(2, path.lost()) + " bar", Palette.ACCENT),
                    Cell.right(SheetUnits.number(2, path.spare()) + " bar", path.set() ? tone(lamp) : Palette.MUTED)), path == picked));
        }
        return items;
    }

    private static String[] stairHead(SheetContext x) {
        return new String[]{x.text("sizing.step"), x.text("sizing.what"), x.text("sizing.lost_bar"), x.text("sizing.after_bar"), x.text("sizing.typed_bar")};
    }

    private static List<Item> stairItems(SheetContext x, PressurePath path) {
        var items = new ArrayList<Item>();
        double at = path.from() + path.head();
        for (var step : path.steps()) {
            at -= step.lost();
            items.add(new Item(null, null, List.of(Cell.of(name(x, step.item()), Palette.TEXT), Cell.of(passage(x, step.passage()), Palette.MUTED), Cell.right(SheetUnits.number(2, step.lost()), Palette.ACCENT),
                    Cell.right(SheetUnits.number(2, at), Palette.ACCENT), Cell.right(step.passage().equals(PressurePath.Step.LINE) ? "" : SheetUnits.number(2, step.typed()), Palette.MUTED)), false));
        }
        return items;
    }

    /** A step's passage: a line, or the side whose door drop it replaces. */
    private static String passage(SheetContext x, String passage) {
        if (passage.startsWith("pa/") && passage.endsWith("/drop")) return x.text("sizing.passage.pa", passage.substring(3, passage.length() - 5));
        return x.text("sizing.passage." + passage);
    }

    private static String[] buildHead(SheetContext x) {
        return new String[]{x.text("sizing.tag"), x.text("sizing.count"), x.text("sizing.equipment"), x.text("sizing.size")};
    }

    private static List<Item> buildItems(SheetContext x, SheetSizing.Sized sized) {
        var items = new ArrayList<Item>();
        for (var entry : sized.units().entrySet()) {
            String id = entry.getKey();
            var size = size(x, id, sized);
            if (size == null || UnitSize.built(size) == null) continue;
            String block = block(id);
            String tag = block == null ? name(x, id) : member(x, id.substring(block.length() + 1));
            items.add(Item.of(id, entry.getValue().rating().lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.right(Integer.toString(size.count()), Palette.ACCENT), Cell.of(equipment(x, id, size), Palette.MUTED),
                    Cell.of(sizeText(x, size.count() > 1 ? one(size) : size), Palette.ACCENT)), false));
        }
        return parented(x, items);
    }

    /** One of a counted size, as the constructor builds each. */
    static UnitSize one(UnitSize size) {
        return switch (size) {
            case ExchangerSizing.Size e -> e.withShells(1);
            case AirCoolerSizing.Size a -> a.withBanks(1);
            case HeaterSizing.Size h -> new HeaterSizing.Size(h.type(), 1, h.diameter(), h.length());
            case TankSizing.Size t -> new TankSizing.Size(t.type(), 1, t.diameter(), t.shell());
            default -> size;
        };
    }

    /** The selected unit's build count and dimensions per physical unit, separate from its calculated limits. */
    static List<String> requirements(SheetContext x, SheetSizing.Sized sized, @Nullable String picked) {
        var size = picked == null ? null : size(x, picked, sized);
        if (size == null || UnitSize.built(size) == null) return List.of();
        return List.of(x.text("sizing.requirements") + ": " + name(x, picked),
                x.text("sizing.build_count", size.count(), equipment(x, picked, size)), x.text("sizing.build_each", sizeText(x, one(size))));
    }

    private static List<String> requirementLines(SheetContext x, SheetSizing.Sized sized, @Nullable String picked, Measure m, int width) {
        var lines = new ArrayList<String>();
        for (var text : requirements(x, sized, picked)) lines.addAll(m.wrap(text, width - 16));
        return lines;
    }

    private static int requirementHeight(SheetContext x, SheetSizing.Sized sized, @Nullable String picked, Measure m, int width) {
        var lines = requirementLines(x, sized, picked, m, width);
        return lines.isEmpty() ? 0 : lines.size() * ROW + 12;
    }

    private static void requirements(Canvas c, SheetContext x, SheetSizing.Sized sized, String picked, Rect box) {
        var lines = requirementLines(x, sized, picked, c.measure(), box.w() - 8);
        if (lines.isEmpty()) return;
        int height = lines.size() * ROW + 8, y = box.y() + 8;
        c.outline(box.x() + 3, box.y() + 2, box.w() - 6, height, Palette.ACCENT);
        for (int i = 0; i < lines.size(); i++, y += ROW) c.text(lines.get(i), box.x() + 8, y, i == 0 ? Palette.TEXT : Palette.ACCENT);
    }

    /** The glass of the view shown. */
    public static void draw(Canvas c, SheetContext x, State state, Rect box) {
        var sized = sized(x);
        if (sized == null) {
            c.text(x.text(x.host().rating() ? "sizing.rating" : "sizing.none"), box.x() + 4, box.y() + 5, Palette.MUTED);
            return;
        }
        var m = c.measure();
        int width = box.w() - 8, y = box.y() + 4;
        c.clip(box);
        switch (state.view) {
            case UNITS -> {
                String picked = unit(state, sized);
                if (picked != null) requirements(c, x, sized, picked, box);
                y += requirementHeight(x, sized, picked, m, width);
                var limits = picked == null ? List.<Item>of() : limitItems(x, sized.units().get(picked).rating());
                String[] limitHead = limitHead(x, x.text("sizing.every_limit", picked == null ? "" : name(x, picked)));
                var lower = Table.of(m, width, limitHead, limits);
                // What the picked item shows beside its limits, and the sheet's fans, wrapped under its table (C2, U1).
                var said = new ArrayList<String>();
                for (var text : beside(x, sized, picked)) said.addAll(m.wrap(text, width));
                int below = (picked == null ? 0 : (limits.size() + 1) * lower.height() + 8) + said.size() * ROW + (said.isEmpty() ? 0 : 6);
                int top = Math.max(box.y() + box.h() / 2, box.bottom() - below);
                split(c, x, box, state, unitHead(x), unitItems(x, state, sized), y, top);
                int end = picked == null ? top + 6 : lower.draw(c, box, limitHead, limits, 0, top + 6, box.bottom()) + 4;
                for (var text : said) {
                    if (end + ROW > box.bottom()) break;
                    c.text(text, box.x() + 4, end, Palette.TEXT);
                    end += ROW;
                }
            }
            case LINES -> {
                String picked = line(state, sized);
                var limits = picked == null ? List.<Item>of() : limitItems(x, sized.lines().get(picked).rating());
                String[] limitHead = limitHead(x, x.text("sizing.every_limit", picked == null ? "" : name(x, picked)));
                var lower = Table.of(m, width, limitHead, limits);
                int below = picked == null ? 0 : (limits.size() + 1) * lower.height() + 8;
                split(c, x, box, state, lineHead(x, x.units()), lineItems(x, state, sized), y, Math.max(box.y() + box.h() / 2, box.bottom() - below));
                if (picked != null) lower.draw(c, box, limitHead, limits, 0, Math.max(box.y() + box.h() / 2, box.bottom() - below) + 6, box.bottom());
            }
            case PRESSURE -> {
                var paths = pathItems(x, state, sized);
                var path = path(state, sized);
                if (path == null) {
                    c.text(x.text("sizing.no_paths"), box.x() + 4, y + 1, Palette.MUTED);
                    break;
                }
                var stair = stairItems(x, path);
                var lower = Table.of(m, width, stairHead(x), stair);
                int below = (stair.size() + 1) * lower.height() + 8 + 40;
                int top = Math.max(box.y() + box.h() / 3, box.bottom() - below);
                split(c, x, box, state, pathHead(x), paths, y, top);
                int end = lower.draw(c, box, stairHead(x), stair, 0, top + 6, box.bottom());
                stairs(c, path, new Rect(box.x() + 8, end + 4, box.w() - 16, box.bottom() - end - 8));
            }
            case BUILD -> {
                var items = buildItems(x, sized);
                int count = sized.units().keySet().stream().map(id -> size(x, id, sized)).filter(size -> size != null && UnitSize.built(size) != null).mapToInt(UnitSize::count).sum();
                for (var text : m.wrap(x.text("sizing.requirements") + " · " + x.text("sizing.build_total", count), width)) {
                    c.text(text, box.x() + 4, y, Palette.ACCENT);
                    y += ROW;
                }
                y += 4;
                Table.of(m, width, buildHead(x), items).draw(c, box, buildHead(x), items, Math.clamp(state.offset, 0, Math.max(0, items.size() - 1)), y, box.bottom());
            }
        }
        c.unclip();
    }

    /**
     * The lines under the Units view's limits: the picked item's values (a free water side's tower water, the duty an air cooler
     * ahead would take, an air cooler's fans), a column's proposed condenser (the door's is the player's), and every air cooler's fans
     * together. They light no lamp.
     */
    static List<String> beside(SheetContext x, SheetSizing.Sized sized, @Nullable String picked) {
        var lines = new ArrayList<String>();
        var rated = picked == null ? null : sized.units().get(picked);
        if (rated != null) {
            var values = rated.rating().values();
            if (values.containsKey(Rating.WATER))
                lines.add(x.text("sizing.value.water", number(values.get(Rating.WATER)), number(values.getOrDefault(Rating.WATER_VOLUME, Double.NaN))));
            if (values.containsKey(Rating.AIR_AHEAD)) lines.add(x.text("sizing.value.air_ahead", number(values.get(Rating.AIR_AHEAD) / 1e6)));
            if (values.containsKey(Rating.FANS))
                lines.add(x.text("sizing.value.fans", SheetUnits.number(0, 100 * values.get(Rating.FANS)) + " %", number(values.getOrDefault(Rating.FAN_POWER, 0.) / 1e3)));
            // A batch still's sizes that follow its pot (batch 2026-11-09-batch-distiller, W7).
            if (values.containsKey(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.RATED))
                lines.add(x.text("sizing.value.still_heater", number(values.get(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.RATED) / 1e3)));
            if (values.getOrDefault(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.CONDENSER, 0.) > 0)
                lines.add(x.text("sizing.value.still_condenser", number(values.get(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.CONDENSER))));
            if (values.getOrDefault(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.COLUMN, 0.) > 0)
                lines.add(x.text("sizing.value.still_column", SheetUnits.number(0, 1000 * values.get(com.wormzjl.icheme.science.flowsheet.sizing.StillSizing.COLUMN))));
        }
        var proposed = picked == null ? null : sized.condensers().get(picked);
        if (proposed != null)
            lines.add(x.text("sizing.value.condenser", x.text("sizing.condenser." + proposed.name().toLowerCase(Locale.ROOT))));
        double fans = sized.fanPower();
        if (fans > 0) lines.add(x.text("sizing.value.fan_total", number(fans / 1e3)));
        return lines;
    }

    /** The item table over a line at {@code bottom}: rows from the state's offset. */
    private static void split(Canvas c, SheetContext x, Rect box, State state, String[] head, List<Item> items, int y, int bottom) {
        var table = Table.of(c.measure(), box.w() - 8, head, items);
        int first = Math.clamp(state.offset, 0, Math.max(0, items.size() - 1));
        table.draw(c, box, head, items, first, y, bottom);
        c.hLine(box.x() + 2, box.right() - 2, bottom + 1, Palette.LINE);
    }

    /** The stair as bars: the pressure after each step, from the path's start to the line its end sets. */
    private static void stairs(Canvas c, PressurePath path, Rect r) {
        if (r.h() < 16 || path.steps().isEmpty()) return;
        double top = path.from() + path.head(), floor = Math.min(path.to(), top - path.lost());
        double low = Math.max(0, Math.min(floor, path.to()) - .1 * (top - floor + 1e-9)), span = Math.max(1e-9, top - low);
        int n = path.steps().size() + 1, w = Math.max(2, r.w() / n);
        c.batch(() -> {
            double at = top;
            for (int i = 0; i < n; i++) {
                if (i > 0) at -= path.steps().get(i - 1).lost();
                int h = (int) Math.round(r.h() * (at - low) / span);
                c.fill(r.x() + i * w + 1, r.bottom() - Math.max(1, h), r.x() + (i + 1) * w - 1, r.bottom(), Palette.LINE);
            }
        });
        int set = r.bottom() - (int) Math.round(r.h() * (path.to() - low) / span);
        c.hLine(r.x(), r.x() + n * w, set, path.set() ? colour(path.rating().lamp()) : Palette.MUTED);
    }

    /** The row key under a point of the glass, or null: an item to pick, a path to show. */
    public static @Nullable String click(SheetContext x, State state, Measure m, Rect box, double px, double py) {
        var sized = sized(x);
        if (sized == null || !box.contains(px, py)) return null;
        List<Item> items;
        String[] head;
        switch (state.view) {
            case UNITS -> {
                items = unitItems(x, state, sized);
                head = unitHead(x);
            }
            case LINES -> {
                items = lineItems(x, state, sized);
                head = lineHead(x, x.units());
            }
            case PRESSURE -> {
                items = pathItems(x, state, sized);
                head = pathHead(x);
            }
            default -> {
                return null;
            }
        }
        var table = Table.of(m, box.w() - 8, head, items);
        int first = Math.clamp(state.offset, 0, Math.max(0, items.size() - 1));
        int header = state.view == View.UNITS ? requirementHeight(x, sized, unit(state, sized), m, box.w() - 8) : 0;
        int row = (int) Math.floor((py - (box.y() + 4 + header + table.height() - 2)) / table.height());
        int i = first + row;
        return row >= 0 && i < items.size() ? items.get(i).key() : null;
    }

    /** A row clicked: the item picked. Its lamps stay; its door shows it. */
    public static boolean pick(State state, @Nullable String key) {
        if (key == null) return false;
        switch (state.view) {
            case UNITS -> state.unit = key;
            case LINES -> state.line = key;
            case PRESSURE -> state.path = Integer.parseInt(key.substring(5));
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The wheel over the glass scrolls the item table. */
    public static void scroll(State state, double dy) {
        state.offset = Math.max(0, state.offset - (int) Math.signum(dy) * 3);
    }
}

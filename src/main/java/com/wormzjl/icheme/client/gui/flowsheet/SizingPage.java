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

/**
 * The Sizing tab (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.8, sheets 24 to 28): four views of one
 * rating. The door holds the view keys, the Design group and the selected item's Auto and Fixed keys, type keys and sizes; the
 * glass holds the table, the selected item's limits and the path's stair. Every key and entry is a fact of the sheet, written
 * through {@link SheetEditor} as one step; the lamps are the last rating's, which the screen rebuilds after an edit. Keys start
 * {@code sizing/}. Minecraft-free.
 */
public final class SizingPage {
    public enum View {
        UNITS, LINES, PRESSURE, BUILD
    }

    /** What the player picked. */
    public static final class State {
        public View view = View.UNITS;
        public @Nullable String unit, line;
        public int path;
        /** The first row the item table shows. */
        public int offset;
    }

    public static final String KEY = "sizing/";
    private static final int ROW = 12, GAP = 8, LAMP = 12, LAMP_SIZE = 5;
    /** Types a tile is built as (plan section 3.10), by tile. */
    private static final Map<String, List<UnitType>> TYPES = Map.of(
            "fired_heater", List.of(UnitType.VERTICAL_HEATER, UnitType.CABIN_HEATER),
            "storage_tank", List.of(UnitType.FLOATING_ROOF, UnitType.SPHERE),
            "drum", List.of(UnitType.STANDING_DRUM, UnitType.LYING_DRUM),
            "compressor", List.of(UnitType.PISTON, UnitType.TURBO));

    private SizingPage() {
    }

    // ---- What the tab reads ----

    /** The rating on show: the last one, where it holds for the run on show; null before the first. */
    public static SheetSizing.@Nullable Sized sized(SheetContext x) {
        var rated = x.host().rated();
        var last = x.host().last();
        return rated != null && last != null && rated.run() == last ? rated.sized() : null;
    }

    /** Whether the tab opens (plan section 3.1 item 1): a steady run, or a run whose sizes stand red until the next is steady. */
    public static boolean open(SheetContext x) {
        var last = x.host().last();
        return last != null && (last.run().status() == FlowsheetRun.Status.STEADY || x.editor().sheet().sizing().any());
    }

    /** Whether Size all may start: a steady run that nothing has greyed, and nothing in hand. */
    public static boolean sizes(SheetContext x) {
        return x.state().steady() && !x.state().running() && x.editor().editable();
    }

    /** The red rows of a rating: units, lines that flow, and paths. Each stops Send to block (plan section 3.1); a yellow row does not. */
    public static int reds(SheetSizing.Sized sized) {
        int red = 0;
        for (var unit : sized.units().values()) if (unit.rating().lamp() == Lamp.RED) red++;
        for (var line : sized.lines().values()) if (line.line().flows() && line.rating().lamp() == Lamp.RED) red++;
        for (var path : sized.paths()) if (path.rating().lamp() == Lamp.RED) red++;
        return red;
    }

    /** What Send to block stores: each unit and member at the size the tab shows ({@link SheetSizing#designs}); none before a rating. */
    public static List<SentDesign> designs(SheetContext x) {
        var sized = sized(x);
        return sized == null ? List.of() : SheetSizing.designs(x.editor().sheet(), sized);
    }

    /**
     * Why Send to block is off, or null while it acts: only the holder sends (D2), on a steady run nothing has greyed, with no red row and
     * something to send, and nothing in hand.
     */
    public static @Nullable String unsent(SheetContext x) {
        var host = x.host();
        if (!host.stores()) return x.text("sizing.send_nowhere");
        if (host.reader()) return x.text("sizing.send_reader");
        if (host.running() != null) return x.text("sizing.send_running");
        var sized = sized(x);
        if (sized == null || !x.state().steady()) return x.text("sizing.send_run");
        int red = reds(sized);
        if (red > 0) return x.text("sizing.send_red", red);
        if (designs(x).isEmpty()) return x.text("sizing.send_none");
        return null;
    }

    private static SizingFacts facts(SheetContext x) {
        return x.editor().sheet().sizing();
    }

    private static @Nullable String unit(State state, SheetSizing.Sized sized) {
        if (state.unit != null && sized.units().containsKey(state.unit)) return state.unit;
        return sized.units().isEmpty() ? null : sized.units().keySet().iterator().next();
    }

    private static @Nullable String line(State state, SheetSizing.Sized sized) {
        if (state.line != null && sized.lines().containsKey(state.line)) return state.line;
        return sized.lines().isEmpty() ? null : sized.lines().keySet().iterator().next();
    }

    private static @Nullable PressurePath path(State state, SheetSizing.Sized sized) {
        var paths = sized.paths();
        return paths.isEmpty() ? null : paths.get(Math.floorMod(state.path, paths.size()));
    }

    /** The size a unit shows: the one its facts hold, else what the rating proposes. */
    private static @Nullable UnitSize size(SheetContext x, String id, SheetSizing.@Nullable Sized sized) {
        var held = facts(x).unit(id).size();
        if (held != null) return held;
        var rated = sized == null ? null : sized.units().get(id);
        return rated == null ? null : rated.size();
    }

    private static LineSizing.@Nullable Size lineSize(SheetContext x, String id, SheetSizing.@Nullable Sized sized) {
        var held = facts(x).line(id).size();
        if (held != null) return held;
        var rated = sized == null ? null : sized.lines().get(id);
        return rated == null ? null : rated.size();
    }

    private static @Nullable String block(String id) {
        int slash = id.indexOf('/');
        return slash < 0 ? null : id.substring(0, slash);
    }

    /** A unit's, a stream's or a member's name: a member under its block's tag. */
    public static String name(SheetContext x, String id) {
        var unit = x.editor().unit(id);
        if (unit != null) return unit.name();
        var stream = x.editor().stream(id);
        if (stream != null) return stream.name();
        String block = block(id);
        if (block == null) return id;
        String own = member(x, id.substring(block.length() + 1));
        var owner = x.editor().unit(block);
        return owner == null ? own : owner.name() + " " + Door.lower(own);
    }

    /** A member's own name: {@code condenser}, {@code pa 2 cooler}. */
    public static String member(SheetContext x, String member) {
        if (member.startsWith("pa ")) {
            String[] words = member.split(" ");
            if (words.length == 3) return x.text("sizing.member.pa_" + words[2], words[1]);
        }
        return x.text("sizing.member." + member.replace(' ', '_'));
    }

    /** The tile a unit or member is: a member's by its size. */
    private static @Nullable String tile(SheetContext x, String id, @Nullable UnitSize size) {
        var unit = x.editor().unit(id);
        if (unit != null) return unit.type();
        return switch (size) {
            case PumpSizing.Size p -> "pump";
            case EjectorSizing.Size e -> "ejector";
            case ExchangerSizing.Size e -> e.kettle() ? "kettle" : "exchanger";
            case AirCoolerSizing.Size a -> "air_cooler";
            case DrumSizing.Size d -> "drum";
            case null, default -> null;
        };
    }

    /** What the item is built as: its type, else its tile's name. */
    private static String equipment(SheetContext x, String id, @Nullable UnitSize size) {
        var type = type(x, id, size);
        if (type != null) return x.text("sizing.equipment." + type.name().toLowerCase(Locale.ROOT));
        String tile = tile(x, id, size);
        return tile == null ? "" : x.t().get(Tiles.name(tile));
    }

    /** The type in use: the player's key, else the size's. */
    private static @Nullable UnitType type(SheetContext x, String id, @Nullable UnitSize size) {
        var held = facts(x).unit(id).type();
        return held != null ? held : SizingFacts.UnitFacts.typeOf(size);
    }

    // ---- Words and numbers ----

    /** A number at the digits its size wants. */
    public static String number(double value) {
        if (!Double.isFinite(value)) return "";
        double a = Math.abs(value);
        return SheetUnits.number(a >= 100 ? 0 : a >= 10 ? 1 : a >= .1 || a == 0 ? 2 : 3, value);
    }

    private static String metres(double value) {
        return SheetUnits.number(2, value);
    }

    /** Limits a size meets or not: a reading of 1 or 0, which the tab says as yes or no. */
    private static final java.util.Set<Limit> YES_NO = java.util.EnumSet.of(Limit.RUN_STEADY, Limit.UNIT_SHAPE, Limit.COLUMN_SOLVES);

    private static String withUnit(String number, String unit) {
        return number.isEmpty() || unit.isEmpty() ? number : number + " " + unit;
    }

    public static String value(SheetContext x, Reading r) {
        if (YES_NO.contains(r.limit())) return x.text(r.met() ? "sizing.yes" : "sizing.no");
        return withUnit(number(r.value()), r.limit().unit());
    }

    /** The limit as a bound: max, min, or from and to. */
    public static String limit(SheetContext x, Reading r) {
        var limit = r.limit();
        if (YES_NO.contains(limit)) return x.text("sizing.yes");
        String unit = limit.unit();
        double low = r.low(), high = r.high();
        return switch (limit.bound().sense()) {
            case MOST -> x.text("sizing.most", withUnit(number(high), unit));
            case LEAST -> x.text("sizing.least", withUnit(number(low), unit));
            case ABOVE -> x.text("sizing.above", withUnit(number(low), unit));
            case WITHIN -> x.text("sizing.within", number(low), withUnit(number(high), unit));
        };
    }

    public static String limitName(SheetContext x, Limit limit) {
        return x.text("limit." + limit.name().toLowerCase(Locale.ROOT));
    }

    /** Where a limit comes from: the world, this plan, physics, or a source's tag as written. */
    public static String source(SheetContext x, Limit limit) {
        String from = limit.source();
        if (from.startsWith("World")) return x.text("sizing.from_world");
        if (from.startsWith("This plan")) return x.text("sizing.from_plan");
        if (from.startsWith("Physics")) return x.text("sizing.from_physics");
        return from;
    }

    public static int colour(Lamp lamp) {
        return switch (lamp) {
            case GREEN -> Palette.TEAL;
            case YELLOW -> Palette.YELLOW;
            case RED -> Palette.RED;
            case NONE -> Palette.LINE;
        };
    }

    private static int tone(Lamp lamp) {
        return lamp == Lamp.RED ? Palette.RED : lamp == Lamp.YELLOW ? Palette.WARN : Palette.ACCENT;
    }

    /** A size in a few words: sheet 24's Size column. */
    public static String sizeText(SheetContext x, @Nullable UnitSize size) {
        String text = switch (size) {
            case PumpSizing.Size p -> x.text("sizing.size.pump", metres(p.discharge()));
            case EjectorSizing.Size e -> x.text("sizing.size.ejector", metres(e.suction()));
            case ExchangerSizing.Size e -> x.text("sizing.size.exchanger", metres(e.shell()), e.tubes());
            case AirCoolerSizing.Size a -> x.text("sizing.size.air_cooler", a.bank().parallel(), a.bank().series(), SheetUnits.number(1, a.width()), a.length());
            case HeaterSizing.Size h -> x.text("sizing.size.heater", SheetUnits.number(1, h.diameter()), h.length());
            case CompressorSizing.Size k -> x.text("sizing.size.compressor", metres(k.size()), k.length());
            case DrumSizing.Size d -> x.text(d.boot() ? "sizing.size.drum_boot" : "sizing.size.drum", SheetUnits.number(1, d.diameter()), d.length());
            case TankSizing.Size t -> x.text("sizing.size.tank", SheetUnits.number(1, t.diameter()), t.shell());
            case ValveSizing.Size v -> x.text("sizing.size.valve", metres(v.bore()));
            case ColumnSizing.Size c -> x.text("sizing.size.column", SheetUnits.number(1, c.diameter()), c.layout().trays(), SheetUnits.number(1, c.height()));
            case null -> "";
        };
        return size != null && size.count() > 1 ? x.text("sizing.size.count", size.count(), text) : text;
    }

    private static String lineSizeText(SheetContext x, LineSizing.@Nullable Size size) {
        if (size == null) return "";
        String bore = metres(size.bore());
        return size.runs() > 1 ? x.text("sizing.size.count", size.runs(), bore) : bore;
    }

    // ---- The door ----

    private static Column.Button position(String key, String text, boolean latched, boolean edits) {
        return new Column.Button(KEY + key, text, edits && !latched, null, latched);
    }

    public static List<Column.Row> rows(SheetContext x, State state) {
        var rows = new ArrayList<Column.Row>();
        rows.add(Door.group(x.text("sizing.view"),
                new Column.Buttons(List.of(Door.position(KEY + "view/0", x.text("sizing.units"), state.view == View.UNITS), Door.position(KEY + "view/1", x.text("sizing.lines"), state.view == View.LINES))),
                new Column.Buttons(List.of(Door.position(KEY + "view/2", x.text("sizing.pressure"), state.view == View.PRESSURE), Door.position(KEY + "view/3", x.text("sizing.build"), state.view == View.BUILD)))));
        rows.add(Door.group(x.text("sizing.design"), Door.entry(KEY + "design_flow", x.text("sizing.design_flow"), "%"), Door.entry(KEY + "line_length", x.text("sizing.line_length"), "m"),
                Door.entry(KEY + "pa_fall", x.text("sizing.pa_fall"), "K")));
        var sized = sized(x);
        if (sized == null) return rows;
        switch (state.view) {
            case UNITS -> {
                String id = unit(state, sized);
                if (id != null) rows.add(unitGroup(x, id, sized));
            }
            case LINES -> {
                String id = line(state, sized);
                if (id != null) rows.add(lineGroup(x, id, sized));
            }
            case PRESSURE -> {
                var path = path(state, sized);
                if (path != null) rows.add(pathGroup(x, state, sized, path));
            }
            case BUILD -> rows.add(buildGroup(x, sized));
        }
        return rows;
    }

    private static Column.Group unitGroup(SheetContext x, String id, SheetSizing.Sized sized) {
        boolean edits = x.editor().editable();
        var held = facts(x).unit(id);
        var size = size(x, id, sized);
        var group = new ArrayList<Column.Row>();
        boolean valve = size instanceof ValveSizing.Size;
        if (!valve) group.add(new Column.Buttons(List.of(position("auto", x.text("sizing.auto"), held.mode() == SizingFacts.Mode.AUTO, edits),
                position("fixed", x.text("sizing.fixed"), held.mode() == SizingFacts.Mode.FIXED, edits))));
        var unit = x.editor().unit(id);
        var types = unit == null ? List.<UnitType>of() : TYPES.getOrDefault(unit.type(), List.of());
        if (!types.isEmpty()) {
            var shown = type(x, id, size);
            var keys = new ArrayList<Column.Button>();
            for (var type : types)
                keys.add(new Column.Button(KEY + "type/" + type.name(), x.text("sizing.type." + type.name().toLowerCase(Locale.ROOT)), edits, x.text(held.type() == type ? "sizing.type_auto_hint" : "sizing.type_hint"), type == shown));
            group.add(new Column.Buttons(keys));
        }
        String hint = x.text("sizing.fixed_hint");
        switch (size) {
            case PumpSizing.Size p -> group.add(Door.entry(KEY + "size/discharge", x.text("sizing.discharge"), "m"));
            case EjectorSizing.Size e -> group.add(Door.entry(KEY + "size/suction", x.text("sizing.suction"), "m"));
            case ExchangerSizing.Size e -> {
                group.add(new Column.Setting(KEY + "size/count", x.text("sizing.shells"), Integer.toString(e.shells()), "", hint, edits));
                group.add(Door.entry(KEY + "size/shell", x.text("sizing.shell"), "m"));
                group.add(Door.entry(KEY + "size/tubes", x.text("sizing.tubes"), "m"));
            }
            case AirCoolerSizing.Size a -> {
                group.add(new Column.Setting(KEY + "size/count", x.text("sizing.banks"), Integer.toString(a.banks()), "", hint, edits));
                group.add(new Column.Setting(KEY + "size/parallel", x.text("sizing.parallel"), Integer.toString(a.bank().parallel()), "", hint, edits));
                group.add(new Column.Setting(KEY + "size/series", x.text("sizing.series"), Integer.toString(a.bank().series()), "", hint, edits));
                group.add(Door.entry(KEY + "size/width", x.text("sizing.width"), "m"));
                group.add(Door.entry(KEY + "size/length", x.text("sizing.length"), "m"));
            }
            case HeaterSizing.Size h -> {
                group.add(new Column.Setting(KEY + "size/count", x.text("sizing.count"), Integer.toString(h.count()), "", hint, edits));
                group.add(Door.entry(KEY + "size/diameter", x.text("sizing.diameter"), "m"));
                group.add(Door.entry(KEY + "size/length", x.text("sizing.length"), "m"));
            }
            case CompressorSizing.Size k -> {
                group.add(Door.entry(KEY + "size/size", x.text("sizing.frame"), "m"));
                group.add(Door.entry(KEY + "size/length", x.text("sizing.length"), "m"));
            }
            case DrumSizing.Size d -> {
                group.add(Door.entry(KEY + "size/diameter", x.text("sizing.diameter"), "m"));
                group.add(Door.entry(KEY + "size/length", x.text("sizing.length"), "m"));
                group.add(new Column.Buttons(List.of(new Column.Button(KEY + "size/boot", x.text("sizing.boot"), edits, x.text("sizing.boot_hint"), d.boot()))));
            }
            case TankSizing.Size t -> {
                group.add(new Column.Setting(KEY + "size/count", x.text("sizing.count"), Integer.toString(t.count()), "", hint, edits));
                group.add(Door.entry(KEY + "size/diameter", x.text("sizing.diameter"), "m"));
                group.add(Door.entry(KEY + "size/shell", x.text("sizing.height"), "m"));
            }
            case ValveSizing.Size v -> group.add(Door.meter(x.text("sizing.bore"), metres(v.bore()), "m"));
            case ColumnSizing.Size c -> {
                group.add(Door.entry(KEY + "size/diameter", x.text("sizing.diameter"), "m"));
                group.add(Door.meter(x.text("sizing.trays"), Integer.toString(c.layout().trays()), ""));
                group.add(Door.meter(x.text("sizing.height"), SheetUnits.number(1, c.height()), "m"));
            }
            case null -> {
                if (unit != null && unit.type().equals("column")) group.add(Door.entry(KEY + "size/diameter", x.text("sizing.diameter"), "m"));
            }
        }
        String tile = tile(x, id, size);
        if ("drum".equals(tile) && unit != null) group.add(Door.entry(KEY + "hold", x.text("sizing.hold"), "min"));
        if ("storage_tank".equals(tile)) group.add(Door.entry(KEY + "hold", x.text("sizing.hold"), "h"));
        return new Column.Group(name(x, id), null, group);
    }

    private static Column.Group lineGroup(SheetContext x, String id, SheetSizing.Sized sized) {
        boolean edits = x.editor().editable();
        var held = x.editor().sheet().sizing().line(id);
        var rated = sized.lines().get(id);
        var size = lineSize(x, id, sized);
        var group = new ArrayList<Column.Row>();
        group.add(new Column.Buttons(List.of(position("line/auto", x.text("sizing.auto"), held.mode() == SizingFacts.Mode.AUTO, edits),
                position("line/fixed", x.text("sizing.fixed"), held.mode() == SizingFacts.Mode.FIXED, edits))));
        var service = held.service() != null ? held.service() : rated.line().service();
        String said = service == null ? x.text("sizing.no_flow") : x.text("sizing.service." + service.name().toLowerCase(Locale.ROOT));
        group.add(new Column.Buttons(List.of(new Column.Button(KEY + "service", said, edits, x.text(held.service() == null ? "sizing.service_hint" : "sizing.service_held_hint")))));
        group.add(Door.entry(KEY + "line/bore", x.text("sizing.bore"), "m"));
        group.add(new Column.Setting(KEY + "line/runs", x.text("sizing.runs"), size == null ? "1" : Integer.toString(size.runs()), "", x.text("sizing.fixed_hint"), edits));
        group.add(Door.entry(KEY + "line/length", x.text("sizing.line_length"), "m"));
        return new Column.Group(name(x, id), null, group);
    }

    private static Column.Group pathGroup(SheetContext x, State state, SheetSizing.Sized sized, PressurePath path) {
        int n = sized.paths().size(), i = Math.floorMod(state.path, n) + 1;
        var group = new ArrayList<Column.Row>();
        group.add(new Column.Setting(KEY + "path", x.text("sizing.path"), x.text("sizing.of", i, n), "", x.text("sizing.path_hint"), n > 1));
        group.add(Door.meter("from", x.text("sizing.from"), metres(path.from()), "bar"));
        group.add(Door.meter("head", x.text("sizing.head"), metres(path.head()), "bar"));
        group.add(Door.meter("to", x.text("sizing.to"), metres(path.to()), "bar"));
        group.add(Door.meter("available", x.text("sizing.available"), metres(path.available()), "bar"));
        group.add(Door.meter("lost", x.text("sizing.lost"), metres(path.lost()), "bar"));
        group.add(Door.meter("spare", x.text("sizing.spare"), metres(path.spare()), "bar"));
        return new Column.Group(x.text("sizing.path_group"), null, group);
    }

    private static Column.Group buildGroup(SheetContext x, SheetSizing.Sized sized) {
        int lines = 0;
        double pipe = 0, largest = 0;
        var basis = facts(x).basis();
        for (var entry : sized.lines().entrySet()) {
            var size = lineSize(x, entry.getKey(), sized);
            if (size == null || !entry.getValue().line().flows()) continue;
            lines++;
            pipe += size.runs() * basis.length(facts(x).line(entry.getKey()).length());
            largest = Math.max(largest, size.bore());
        }
        return Door.group(x.text("sizing.lines"), Door.meter("count", x.text("sizing.line_count"), Integer.toString(lines), ""), Door.meter("pipe", x.text("sizing.pipe"), SheetUnits.number(0, pipe), "m"),
                Door.meter("largest", x.text("sizing.largest"), metres(largest), "m"));
    }

    /** What the entries hold. */
    public static Map<String, String> values(SheetContext x, State state) {
        var values = new HashMap<String, String>();
        var basis = facts(x).basis();
        values.put(KEY + "design_flow", SheetUnits.number(0, basis.designFlow() * 100));
        values.put(KEY + "line_length", SheetUnits.number(0, basis.lineLength()));
        values.put(KEY + "pa_fall", SheetUnits.number(0, basis.paFall()));
        var sized = sized(x);
        if (sized == null) return values;
        String unit = unit(state, sized);
        if (state.view == View.UNITS && unit != null) {
            switch (size(x, unit, sized)) {
                case PumpSizing.Size p -> values.put(KEY + "size/discharge", metres(p.discharge()));
                case EjectorSizing.Size e -> values.put(KEY + "size/suction", metres(e.suction()));
                case ExchangerSizing.Size e -> {
                    values.put(KEY + "size/shell", metres(e.shell()));
                    values.put(KEY + "size/tubes", Integer.toString(e.tubes()));
                }
                case AirCoolerSizing.Size a -> {
                    values.put(KEY + "size/width", SheetUnits.number(1, a.width()));
                    values.put(KEY + "size/length", Integer.toString(a.length()));
                }
                case HeaterSizing.Size h -> {
                    values.put(KEY + "size/diameter", SheetUnits.number(1, h.diameter()));
                    values.put(KEY + "size/length", Integer.toString(h.length()));
                }
                case CompressorSizing.Size k -> {
                    values.put(KEY + "size/size", metres(k.size()));
                    values.put(KEY + "size/length", Integer.toString(k.length()));
                }
                case DrumSizing.Size d -> {
                    values.put(KEY + "size/diameter", SheetUnits.number(1, d.diameter()));
                    values.put(KEY + "size/length", Integer.toString(d.length()));
                }
                case TankSizing.Size t -> {
                    values.put(KEY + "size/diameter", SheetUnits.number(1, t.diameter()));
                    values.put(KEY + "size/shell", Integer.toString(t.shell()));
                }
                case null, default -> {
                }
            }
            var column = x.editor().unit(unit);
            if (column != null && column.type().equals("column")) values.put(KEY + "size/diameter", SheetUnits.number(1, ColumnDoor.of(column.params()).diameter()));
            var hold = facts(x).unit(unit).hold();
            values.put(KEY + "hold", hold == null ? "" : number(hold));
        }
        String line = line(state, sized);
        if (state.view == View.LINES && line != null) {
            var size = lineSize(x, line, sized);
            values.put(KEY + "line/bore", size == null ? "" : metres(size.bore()));
            values.put(KEY + "line/length", SheetUnits.number(0, facts(x).basis().length(facts(x).line(line).length())));
        }
        return values;
    }

    /** An entry typed: the basis, a hold, or a size, which keeps the item Fixed there. A value the item cannot take changes nothing. */
    public static void entry(SheetContext x, State state, String key, String text) {
        if (!key.startsWith(KEY)) return;
        key = key.substring(KEY.length());
        var value = SheetContext.number(text);
        String step = "entry sizing " + key;
        var facts = facts(x);
        var basis = facts.basis();
        try {
            switch (key) {
                case "design_flow" -> {
                    if (value != null) x.editor().sizing(facts.with(new DesignBasis(value / 100, basis.lineLength(), basis.paFall())), step);
                }
                case "line_length" -> {
                    if (value != null) x.editor().sizing(facts.with(new DesignBasis(basis.designFlow(), value, basis.paFall())), step);
                }
                case "pa_fall" -> {
                    if (value != null) x.editor().sizing(facts.with(new DesignBasis(basis.designFlow(), basis.lineLength(), value)), step);
                }
                default -> typed(x, state, key, value, step);
            }
        } catch (IllegalArgumentException refused) {
            // A size the constructor's records refuse stays as it was; the entry shows what the player typed until the next rebuild.
        }
    }

    private static void typed(SheetContext x, State state, String key, @Nullable Double value, String step) {
        var sized = sized(x);
        if (sized == null) return;
        var facts = facts(x);
        if (state.view == View.LINES) {
            String id = line(state, sized);
            if (id == null) return;
            var held = facts.line(id);
            if (key.equals("line/length")) x.editor().sizing(facts.with(id, held.lasting(value == null || !(value > 0) ? null : value)), step);
            else if (key.equals("line/bore") && value != null) {
                var size = lineSize(x, id, sized);
                x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.FIXED).with(new LineSizing.Size(value, size == null ? 1 : size.runs()))), step);
            }
            return;
        }
        String id = unit(state, sized);
        if (id == null) return;
        var held = facts.unit(id);
        if (key.equals("hold")) {
            x.editor().sizing(facts.with(id, held.holding(value == null || !(value > 0) ? null : value)), step);
            return;
        }
        if (value == null || !key.startsWith("size/")) return;
        var unit = x.editor().unit(id);
        if (unit != null && unit.type().equals("column") && key.equals("size/diameter")) {
            var json = unit.params().json().deepCopy();
            json.addProperty("diameter", value);
            x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.FIXED)), id, new Params(json), step);
            return;
        }
        var size = size(x, id, sized);
        if (size == null) return;
        var next = resized(size, key.substring(5), value);
        if (next != null) keep(x, id, next, step);
    }

    /** {@code size} with one field at {@code value}; null for a field it has not. Throws for a value its record refuses. */
    private static @Nullable UnitSize resized(UnitSize size, String field, double value) {
        int whole = (int) Math.round(value);
        return switch (size) {
            case PumpSizing.Size p -> field.equals("discharge") ? new PumpSizing.Size(value) : null;
            case EjectorSizing.Size e -> field.equals("suction") ? new EjectorSizing.Size(value) : null;
            case ExchangerSizing.Size e -> switch (field) {
                case "count" -> e.withShells(whole);
                case "shell" -> new ExchangerSizing.Size(e.kettle(), e.shells(), value, e.tubes());
                case "tubes" -> new ExchangerSizing.Size(e.kettle(), e.shells(), e.shell(), whole);
                default -> null;
            };
            case AirCoolerSizing.Size a -> switch (field) {
                case "count" -> a.withBanks(whole);
                case "parallel" -> new AirCoolerSizing.Size(a.width(), a.length(), new ProcessUnit.Bank(whole, a.bank().series()), a.banks());
                case "series" -> new AirCoolerSizing.Size(a.width(), a.length(), new ProcessUnit.Bank(a.bank().parallel(), whole), a.banks());
                case "width" -> new AirCoolerSizing.Size(value, a.length(), a.bank(), a.banks());
                case "length" -> new AirCoolerSizing.Size(a.width(), whole, a.bank(), a.banks());
                default -> null;
            };
            case HeaterSizing.Size h -> switch (field) {
                case "count" -> new HeaterSizing.Size(h.type(), whole, h.diameter(), h.length());
                case "diameter" -> new HeaterSizing.Size(h.type(), h.count(), value, h.length());
                case "length" -> new HeaterSizing.Size(h.type(), h.count(), h.diameter(), whole);
                default -> null;
            };
            case CompressorSizing.Size k -> switch (field) {
                case "size" -> new CompressorSizing.Size(k.type(), value, k.length());
                case "length" -> new CompressorSizing.Size(k.type(), k.size(), whole);
                default -> null;
            };
            case DrumSizing.Size d -> switch (field) {
                case "diameter" -> new DrumSizing.Size(d.type(), value, d.length(), d.boot());
                case "length" -> new DrumSizing.Size(d.type(), d.diameter(), whole, d.boot());
                default -> null;
            };
            case TankSizing.Size t -> switch (field) {
                case "count" -> new TankSizing.Size(t.type(), whole, t.diameter(), t.shell());
                case "diameter" -> new TankSizing.Size(t.type(), t.count(), value, t.shell());
                case "shell" -> new TankSizing.Size(t.type(), t.count(), t.diameter(), whole);
                default -> null;
            };
            case ValveSizing.Size v -> null;
            case ColumnSizing.Size c -> null;
        };
    }

    /**
     * Keeps {@code size} on item {@code id}, Fixed, in one step. A member's count is its column door's (S3b): a condenser's, a
     * reboiler's or a pumparound cooler's count writes the door in the same step.
     */
    private static void keep(SheetContext x, String id, UnitSize size, @Nullable String step) {
        var facts = facts(x);
        var next = facts.with(id, facts.unit(id).with(SizingFacts.Mode.FIXED).with(size));
        String block = block(id);
        var column = block == null ? null : x.editor().unit(block);
        var json = column == null ? null : door(column, id.substring(block.length() + 1), size.count());
        if (json == null) x.editor().sizing(next, step);
        else x.editor().sizing(next, block, new Params(json), step);
    }

    /** The column's settings with member {@code member}'s count at {@code count}, or null for a member that has none on the door. */
    private static @Nullable JsonObject door(Flowsheet.Unit column, String member, int count) {
        var json = column.params().json().deepCopy();
        int units = Math.clamp(count, 1, ColumnComponents.MAX_UNITS);
        switch (member) {
            case "condenser" -> json.addProperty(ColumnDoor.CONDENSER_UNITS, units);
            case "reboiler" -> json.addProperty(ColumnDoor.KETTLE_UNITS, units);
            default -> {
                if (!member.startsWith("pa ") || !member.endsWith(" cooler") || !(json.get("pumparounds") instanceof JsonArray rows)) return null;
                String pa = member.substring(3, member.length() - " cooler".length());
                var door = ColumnDoor.of(column.params());
                for (int i = 0; i < door.pumparounds().size(); i++)
                    if (door.pumparounds().get(i).id().equals(pa) && rows.get(i) instanceof JsonObject row) {
                        row.addProperty(ColumnDoor.UNITS, units);
                        return json;
                    }
                return null;
            }
        }
        return json;
    }

    /** A key pressed; false for one this page does not take. */
    public static boolean press(SheetContext x, State state, String key) {
        if (!key.startsWith(KEY)) return false;
        key = key.substring(KEY.length());
        if (key.startsWith("view/")) {
            state.view = View.values()[Integer.parseInt(key.substring(5))];
            state.offset = 0;
            return true;
        }
        if (key.equals("path-") || key.equals("path+")) {
            state.path += key.endsWith("+") ? 1 : -1;
            return true;
        }
        var sized = sized(x);
        if (sized == null) return true;
        var facts = facts(x);
        try {
            if (key.startsWith("line/") || key.equals("service")) lineKey(x, state, sized, facts, key);
            else unitKey(x, state, sized, facts, key);
        } catch (IllegalArgumentException refused) {
            // A step past what the record takes changes nothing.
        }
        return true;
    }

    private static void lineKey(SheetContext x, State state, SheetSizing.Sized sized, SizingFacts facts, String key) {
        String id = line(state, sized);
        if (id == null) return;
        var held = facts.line(id);
        switch (key) {
            case "line/auto" -> x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.AUTO)), null);
            case "line/fixed" -> {
                var kept = held.size() == null ? lineSize(x, id, sized) : held.size();
                x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.FIXED).with(kept)), null);
            }
            case "service" -> {
                // Round the services, then back to what the stream says.
                var all = Service.values();
                var now = held.service();
                var next = now == null ? all[0] : now.ordinal() + 1 < all.length ? all[now.ordinal() + 1] : null;
                x.editor().sizing(facts.with(id, held.serving(next)), null);
            }
            case "line/runs-", "line/runs+" -> {
                var size = lineSize(x, id, sized);
                if (size == null) return;
                int runs = size.runs() + (key.endsWith("+") ? 1 : -1);
                if (runs >= 1) x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.FIXED).with(new LineSizing.Size(size.bore(), runs))), null);
            }
            default -> {
            }
        }
    }

    private static void unitKey(SheetContext x, State state, SheetSizing.Sized sized, SizingFacts facts, String key) {
        String id = unit(state, sized);
        if (id == null) return;
        var held = facts.unit(id);
        var size = size(x, id, sized);
        if (key.equals("auto")) x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.AUTO)), null);
        else if (key.equals("fixed")) {
            // A column's and a valve's size is the door's or the line's; every other Fixed item keeps the size it shows.
            boolean own = !(size instanceof ColumnSizing.Size) && !(size instanceof ValveSizing.Size);
            x.editor().sizing(facts.with(id, held.with(SizingFacts.Mode.FIXED).with(own ? size : held.size())), null);
        } else if (key.startsWith("type/")) {
            var type = UnitType.valueOf(key.substring(5));
            x.editor().sizing(facts.with(id, held.typed(held.type() == type ? null : type)), null);
        } else if (key.equals("size/boot") && size instanceof DrumSizing.Size d) keep(x, id, new DrumSizing.Size(d.type(), d.diameter(), d.length(), !d.boot()), null);
        else if (key.startsWith("size/") && (key.endsWith("-") || key.endsWith("+")) && size != null) {
            String field = key.substring(5, key.length() - 1);
            int by = key.endsWith("+") ? 1 : -1;
            int now = switch (field) {
                case "parallel" -> size instanceof AirCoolerSizing.Size a ? a.bank().parallel() : 1;
                case "series" -> size instanceof AirCoolerSizing.Size a ? a.bank().series() : 1;
                default -> size.count();
            };
            var next = resized(size, field, now + by);
            if (next != null) keep(x, id, next, null);
        }
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
    private record Item(@Nullable String key, @Nullable Lamp lamp, List<Cell> cells, boolean picked) {
    }

    /**
     * A table laid out on its cells' own widths: one line a row where every column fits the width, else as many as it takes, each
     * column whole on its line and the later lines indented under the first column (the GUI skill's stacking rule: no cut cell).
     */
    private record Table(int[] at, int[] line, int[] widths, int lines) {
        private static final int INDENT = 16;

        static Table of(Measure m, int width, String[] head, List<Item> items) {
            int n = head.length;
            int[] widths = new int[n];
            for (int i = 0; i < n; i++) widths[i] = m.width(head[i]);
            for (var item : items) for (int i = 0; i < n && i < item.cells().size(); i++) widths[i] = Math.max(widths[i], m.width(item.cells().get(i).text()));
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
            int x = x0 + at[i] + (cell.right() ? widths[i] - c.measure().width(cell.text()) : 0);
            c.text(cell.text(), x, y + line[i] * ROW, cell.colour());
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
                for (int i = 0; i < item.cells().size(); i++) cell(c, item.cells().get(i), i, x0, y);
            }
            return y;
        }
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
            String tag = block == null ? name(x, id) : "  " + member(x, id.substring(block.length() + 1));
            items.add(new Item(id, rated.rating().lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.of(equipment(x, id, size), Palette.MUTED), Cell.of(sizeText(x, size), Palette.ACCENT),
                    Cell.of(closest == null ? "" : limitName(x, closest.limit()), Palette.MUTED), Cell.right(closest == null ? "" : value(x, closest), closest == null ? Palette.ACCENT : tone(closest.lamp())),
                    Cell.right(closest == null ? "" : limit(x, closest), Palette.MUTED)), id.equals(picked)));
        }
        return items;
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
            String tag = block == null ? name(x, id) : "  " + member(x, id.substring(block.length() + 1));
            items.add(new Item(id, rating.lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.of(service == null ? "" : x.text("sizing.service." + service.name().toLowerCase(Locale.ROOT)), Palette.MUTED),
                    Cell.right(line.flows() ? number(u.time().display(line.volume())) : "", Palette.ACCENT), Cell.right(lineSizeText(x, lineSize(x, id, sized)), Palette.ACCENT),
                    reading(rating, service == null ? null : service.speed()), reading(rating, service == null ? null : service.drop()), reading(rating, service == null ? null : service.momentum()),
                    Cell.of(closest == null ? "" : limitName(x, closest.limit()), Palette.MUTED)), id.equals(picked)));
        }
        return items;
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
                    Cell.right(metres(path.available()) + " bar", Palette.ACCENT), Cell.right(metres(path.lost()) + " bar", Palette.ACCENT),
                    Cell.right(metres(path.spare()) + " bar", path.set() ? tone(lamp) : Palette.MUTED)), path == picked));
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
            items.add(new Item(null, null, List.of(Cell.of(name(x, step.item()), Palette.TEXT), Cell.of(passage(x, step.passage()), Palette.MUTED), Cell.right(metres(step.lost()), Palette.ACCENT),
                    Cell.right(metres(at), Palette.ACCENT), Cell.right(step.passage().equals(PressurePath.Step.LINE) ? "" : metres(step.typed()), Palette.MUTED)), false));
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
            String tag = block == null ? name(x, id) : "  " + member(x, id.substring(block.length() + 1));
            items.add(new Item(id, entry.getValue().rating().lamp(), List.of(Cell.of(tag, Palette.TEXT), Cell.right(Integer.toString(size.count()), Palette.ACCENT), Cell.of(equipment(x, id, size), Palette.MUTED),
                    Cell.of(sizeText(x, size.count() > 1 ? one(size) : size), Palette.ACCENT)), false));
        }
        return items;
    }

    /** One of a counted size, as the constructor builds each. */
    private static UnitSize one(UnitSize size) {
        return switch (size) {
            case ExchangerSizing.Size e -> e.withShells(1);
            case AirCoolerSizing.Size a -> a.withBanks(1);
            case HeaterSizing.Size h -> new HeaterSizing.Size(h.type(), 1, h.diameter(), h.length());
            case TankSizing.Size t -> new TankSizing.Size(t.type(), 1, t.diameter(), t.shell());
            default -> size;
        };
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
                var limits = picked == null ? List.<Item>of() : limitItems(x, sized.units().get(picked).rating());
                String[] limitHead = limitHead(x, x.text("sizing.every_limit", picked == null ? "" : name(x, picked)));
                var lower = Table.of(m, width, limitHead, limits);
                int below = picked == null ? 0 : (limits.size() + 1) * lower.height() + 8;
                split(c, x, box, state, unitHead(x), unitItems(x, state, sized), y, Math.max(box.y() + box.h() / 2, box.bottom() - below));
                if (picked != null) lower.draw(c, box, limitHead, limits, 0, Math.max(box.y() + box.h() / 2, box.bottom() - below) + 6, box.bottom());
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
                Table.of(m, width, buildHead(x), items).draw(c, box, buildHead(x), items, Math.clamp(state.offset, 0, Math.max(0, items.size() - 1)), y, box.bottom());
            }
        }
        c.unclip();
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
        double at = top;
        for (int i = 0; i < n; i++) {
            if (i > 0) at -= path.steps().get(i - 1).lost();
            int h = (int) Math.round(r.h() * (at - low) / span);
            c.fill(r.x() + i * w + 1, r.bottom() - Math.max(1, h), r.x() + (i + 1) * w - 1, r.bottom(), Palette.LINE);
        }
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
        int row = (int) Math.floor((py - (box.y() + 4 + table.height() - 2)) / table.height());
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

    /** What the status line says on the tab. */
    public static SheetState.Status status(SheetContext x) {
        String broken = x.host().broken();
        if (broken != null) return new SheetState.Status(x.text("sizing.broken", broken), Palette.WARN);
        var sized = sized(x);
        if (sized == null) return new SheetState.Status(x.text(x.host().rating() ? "sizing.rating" : "sizing.none"), Palette.MUTED);
        var last = x.host().last();
        if (last == null || last.run().status() != FlowsheetRun.Status.STEADY) return new SheetState.Status(x.text("sizing.unsteady"), Palette.WARN);
        int red = 0, yellow = 0, units = sized.units().size(), lines = 0;
        String first = null;
        for (var entry : sized.units().entrySet()) {
            var lamp = entry.getValue().rating().lamp();
            if (lamp == Lamp.RED) red++;
            if (lamp == Lamp.YELLOW) yellow++;
            if (first == null && lamp == Lamp.RED) first = missed(x, entry.getKey(), entry.getValue().rating());
        }
        for (var entry : sized.lines().entrySet()) {
            if (!entry.getValue().line().flows()) continue;
            lines++;
            var lamp = entry.getValue().rating().lamp();
            if (lamp == Lamp.RED) red++;
            if (lamp == Lamp.YELLOW) yellow++;
            if (first == null && lamp == Lamp.RED) first = missed(x, entry.getKey(), entry.getValue().rating());
        }
        for (var path : sized.paths()) {
            var lamp = path.rating().lamp();
            if (lamp == Lamp.YELLOW) yellow++;
            if (lamp != Lamp.RED) continue;
            red++;
            if (first == null) first = missed(x, path.start(), path.rating());
        }
        if (first == null && yellow > 0) for (var entry : sized.units().entrySet())
            if (entry.getValue().rating().lamp() == Lamp.YELLOW) {
                first = missed(x, entry.getKey(), entry.getValue().rating());
                break;
            }
        if (first == null && yellow > 0) for (var path : sized.paths())
            if (path.rating().lamp() == Lamp.YELLOW) {
                first = missed(x, path.start(), path.rating());
                break;
            }
        String lamps = red + yellow == 0 ? x.text("sizing.all_met") : first == null ? x.text("sizing.missed", red, yellow) : x.text("sizing.missed_first", red, yellow, first);
        String design = SheetUnits.number(0, facts(x).basis().designFlow() * 100);
        String text = x.text(x.editor().sheet().sizing().any() ? "sizing.status" : "sizing.status_proposed", design, units, lines, lamps);
        if (x.state().edited()) text = x.text("status.edited") + " " + text;
        return new SheetState.Status(text, red > 0 || yellow > 0 ? Palette.WARN : x.state().edited() ? Palette.MUTED : Palette.ACCENT);
    }

    /** An item's first miss: its tag, the limit, its value and the bound. */
    private static String missed(SheetContext x, String id, Rating rating) {
        var closest = rating.closest();
        if (closest == null) return name(x, id);
        return x.text("sizing.miss", name(x, id), Door.lower(limitName(x, closest.limit())), value(x, closest), limit(x, closest));
    }

    // ---- The footer ----

    /** The tab's footer: Close, Size all (the confirming key) or Stop while it runs, Send to block ({@link #unsent}), Undo and Redo where they fit. */
    public static List<SimulatorPage.Slot> footer(SheetContext x, SimulatorFrame frame, SimulatorHost host, SheetEditor editor) {
        var slots = new ArrayList<SimulatorPage.Slot>();
        var at = frame.sizingFooter();
        boolean running = host.running() != null;
        slots.add(new SimulatorPage.Slot(new Column.Button("close", x.t().get("fluid.close"), true, null), at.get(0)));
        if (running) slots.add(new SimulatorPage.Slot(new Column.Button("stop", x.text("frame.stop"), true, null), at.get(1)));
        else {
            String why = host.reader() ? x.text("sizing.size_all_reader") : x.state().steady() ? null : x.text("sizing.size_all_hint");
            slots.add(new SimulatorPage.Slot(new Column.Button("size_all", x.text("sizing.size_all"), host.canRun() && sizes(x), why), at.get(1)));
        }
        boolean narrow = frame.size() != SimulatorFrame.Size.WIDE;
        String unsent = unsent(x);
        String send = unsent != null ? unsent : x.text("sizing.send_hint", designs(x).size());
        slots.add(new SimulatorPage.Slot(new Column.Button("send", x.text(narrow ? "sizing.send_short" : "sizing.send"), unsent == null, send), at.get(2)));
        if (at.size() > 3) {
            slots.add(new SimulatorPage.Slot(new Column.Button("undo", x.text("frame.undo"), editor.canUndo(), null), at.get(3)));
            slots.add(new SimulatorPage.Slot(new Column.Button("redo", x.text("frame.redo"), editor.canRedo(), null), at.get(4)));
        }
        return slots;
    }
}

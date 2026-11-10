package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The selection's panel over the canvas's right end (batch 2026-11-02-process-simulator, D1, sheet 02): a unit's readings, a feed's settings as entries that
 * take effect as typed, a made stream's properties as meters, a placeholder's keys. Its keys: {@code open}, {@code delete}, {@code replace}, {@code copy}.
 */
public final class SelectionColumn {
    /** The panel's rows and what its entries show. */
    public record Panel(List<Column.Row> rows, Map<String, String> values) {
    }

    private SelectionColumn() {
    }

    public static Panel of(SheetContext x, SheetGestures.Selection selected) {
        return selected.unit() ? unit(x, x.editor().unit(selected.id())) : stream(x, x.editor().stream(selected.id()));
    }

    private static Panel wrap(SheetContext x, String name, List<Column.Row> rows, Map<String, String> values) {
        var all = new ArrayList<Column.Row>();
        all.add(new Column.Wide("name", null));
        all.addAll(rows);
        var shown = new LinkedHashMap<>(values);
        shown.put("name", name);
        return new Panel(List.of(new Column.Group(x.text("panel.selected"), x.text("panel.selected.hint"), all)), shown);
    }

    private static Column.Buttons keys(SheetContext x, String first, boolean active) {
        return new Column.Buttons(List.of(Door.key(first, x.text("panel." + first), active, active ? null : x.text("hint.clipboard")), Door.key("delete", x.text("panel.delete"), true, null)));
    }

    private static Panel unit(SheetContext x, Flowsheet.Unit unit) {
        var box = x.editor().layout().box(unit.id());
        var rows = new ArrayList<Column.Row>();
        var u = x.units();
        if (box.placeholder()) {
            rows.add(new Column.Line(x.text("panel.removed"), Palette.WARN));
            rows.add(new Column.Buttons(List.of(Door.key("replace", x.text("panel.replace"), true, null), Door.key("delete", x.text("panel.delete"), true, null))));
            return wrap(x, unit.name(), rows, Map.of());
        }
        rows.add(new Column.Line(x.t().get(Tiles.name(unit.type())), Palette.MUTED));
        var read = Reader.of(x, unit.id());
        switch (unit.type()) {
            case "valve" -> {
                rows.add(Door.meter(x.text("door.drop"), read.of("drop", u.pressure().si(1), 2), u.pressure().symbol()));
                rows.add(Door.meter(x.text("door.cooled"), read.of("cooled", 1, 1), "K"));
            }
            case "exchanger", "kettle" -> {
                rows.add(Door.meter(x.text("door.duty"), read.of("duty", 1e6, 2), "MW"));
                addOut(x, rows, x.outOf(unit.id(), "tube_out"), "panel.tube_out");
                addOut(x, rows, x.outOf(unit.id(), "shell_out"), "panel.shell_out");
                rows.add(Door.meter(x.text("door.approach"), read.of("approach", 1, 1), "K"));
            }
            case "cooler" -> rows.add(Door.meter(x.text("door.duty"), read.of("duty", 1e6, 2), "MW"));
            case "fired_heater" -> {
                rows.add(Door.meter(x.text("door.duty"), read.of("duty", 1e6, 2), "MW"));
                rows.add(Door.meter(x.text("door.vapour_out"), read.of("vapour", .01, 1), "%"));
            }
            case "pump", "compressor" -> {
                rows.add(Door.meter(x.text("door.power"), read.of("power", 1e3, 1), "kW"));
                rows.add(Door.meter(x.text("door.rise"), read.of("rise", u.pressure().si(1), 2), u.pressure().symbol()));
            }
            case "combustion_engine", "gas_turbine" -> {
                rows.add(Door.meter(x.text("door.power"), read.of("power", 1e6, 2), "MW"));
                rows.add(Door.meter(x.text("door.efficiency"), read.of("efficiency", .01, 1), "%"));
            }
            case "steam_turbine" -> {
                rows.add(Door.meter(x.text("door.power"), read.of("power", 1e3, 1), "kW"));
                rows.add(Door.meter(x.text("door.steam_rate"), read.of("steam_rate", 1, 2), "kg/kWh"));
            }
            case "vacuum_pump" -> {
                rows.add(Door.meter(x.text("door.power"), read.of("power", 1e3, 1), "kW"));
                rows.add(Door.meter(x.text("door.capacity"), read.of("capacity", 1 / 3600., 0), "m³/h"));
            }
            case "ejector" -> {
                rows.add(Door.meter(x.text("door.steam_in"), read.of("motive", 1 / 3600., 0), "kg/h"));
                rows.add(Door.meter(x.text("door.compression"), read.of("compression", 1, 1), ""));
            }
            case "drum", "storage_tank" -> {
                rows.add(Door.meter(x.text("door.temperature"), read.temperature(u), u.temperature().symbol()));
                rows.add(Door.meter(x.text("door.pressure"), read.pressure(u), u.pressure().symbol()));
            }
            case "column" -> {
                rows.add(Door.meter(x.text("door.condenser"), read.of("condenser", 1e6, 2), "MW"));
                rows.add(Door.meter(x.text("door.reboiler"), read.of("reboiler", 1e6, 2), "MW"));
            }
            default -> {
            }
        }
        rows.add(keys(x, "open", true));
        return wrap(x, unit.name(), rows, Map.of());
    }

    private static void addOut(SheetContext x, List<Column.Row> rows, Flowsheet.Stream stream, String key) {
        var props = x.props(stream);
        rows.add(Door.meter(x.text(key), props == null ? "" : x.units().t(props.temperature()), x.units().temperature().symbol()));
    }

    private static Panel stream(SheetContext x, Flowsheet.Stream stream) {
        var u = x.units();
        var rows = new ArrayList<Column.Row>();
        var p = x.props(stream);
        if (stream.feed() != null && stream.from() == null) {
            var feed = stream.feed();
            rows.add(new Column.Line(stream.to() == null ? x.text("panel.feed_open") : x.text("panel.feed_to", name(x, stream.to().unit())), Palette.MUTED));
            rows.add(Door.entry("temperature", x.text("door.temperature"), u.temperature().symbol()));
            rows.add(Door.entry("pressure", x.text("door.pressure"), u.pressure().symbol()));
            rows.add(StreamDoors.flowEntry(x, stream));
            rows.add(new Column.Line(x.text("panel.parts", feed.amounts().size()), Palette.MUTED));
            rows.add(keys(x, "open", true));
            return wrap(x, stream.name(), rows, Map.of("temperature", u.t(feed.temperature()), "pressure", u.p(feed.pressure()), "flow", x.feedFlow(stream)));
        }
        String from = stream.from() == null ? "" : name(x, stream.from().unit()), to = stream.to() == null ? "" : name(x, stream.to().unit());
        rows.add(new Column.Line(stream.to() == null ? x.text("panel.product_from", from) : stream.from() == null ? x.text("panel.feed_to", to) : x.text("panel.from_to", from, to), Palette.MUTED));
        rows.add(Door.meter(x.text("door.temperature"), p == null ? "" : u.t(p.temperature()), u.temperature().symbol()));
        rows.add(Door.meter(x.text("door.pressure"), p == null ? "" : u.p(p.pressure()), u.pressure().symbol()));
        rows.add(Door.meter(x.text("door.vapour"), p == null ? "" : SheetUnits.number(1, p.vapour()), "%"));
        // The flow on the display key's basis; in Mass a separate mass meter would say it twice.
        rows.add(Door.meter(x.text("door.flow"), p == null ? "" : u.flow(p.moles(), p.mass(), p.density()), u.flowUnit()));
        rows.add(Door.meter(x.text("door.density"), p == null ? "" : SheetUnits.number(1, p.density()), "kg/m³"));
        rows.add(new Column.Buttons(List.of(Door.key("open", x.text("panel.open"), true, null), Door.key("copy", x.text("door.copy"), p != null, x.text("hint.clipboard")))));
        return wrap(x, stream.name(), rows, Map.of());
    }

    private static String name(SheetContext x, String unit) {
        var at = x.editor().unit(unit);
        return at == null ? unit : at.name();
    }
}

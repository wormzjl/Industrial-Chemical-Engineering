package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Connection;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Mark;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Metric;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Page;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnInspection;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * The column's door (batch 2026-11-02-process-simulator, sheet 12): the calculator's rows and pages, read from the unit's settings. Flow and temperature are
 * meters read from the feed stream, a wired steam feed's likewise, and its tray stays the player's. The Feed page is off, for a stream sets the feed. The
 * Results pages read the display result the column's last solve left on its report; their glass ports the calculator screen's, with no Minecraft class. The
 * Equipment page lists the block's connection points. The calculator's own screen stays until W8 swaps the block.
 */
public final class ColumnDoors {
    private static final String KEY = "column.";
    private static final int MAX_PA = 4, MAX_STEAM = 2;
    private static final double KMOL = 3.6;

    private ColumnDoors() {
    }

    private static String c(SheetContext x, String key, Object... args) {
        return x.t().get(KEY + key, args);
    }

    /**
     * The column as its door stands, on its feed: what Save design and a template take of it, with no solve. Null with no feed yet, or with a door {@code V3ColumnInput} refuses, whose
     * Run says why. A feed's parts are what its line last held, or what the sheet sets.
     */
    public static @Nullable V3ColumnInput input(SheetContext x, String id) {
        var stream = x.into(id, "feed");
        if (stream == null) return null;
        var axis = x.fluids().axis();
        var flows = new double[x.fluids().pack().components().size()];
        var components = x.fluids().pack().components();
        var last = x.last(stream);
        var feed = stream.feed();
        double temperature;
        if (stream.from() == null && feed != null) {
            for (int i = 0; i < flows.length; i++) flows[i] = feed.amounts().getOrDefault(components.get(i), 0.0);
            temperature = feed.temperature();
        } else if (last != null) {
            var parts = last.amountsView();
            for (int i = 0; i < flows.length; i++) {
                int at = axis.indexOf(components.get(i));
                if (at >= 0 && at < parts.length) flows[i] = parts[at];
            }
            temperature = last.temperature();
        } else return null;
        try {
            return ColumnDoor.of(x.editor().unit(id).params()).input(x.fluids().pack(), flows, temperature);
        } catch (IllegalArgumentException refused) {
            return null;
        }
    }

    /** What the Equipment page has chosen. */
    public static ColumnComponents.Choices choices(Flowsheet.Unit unit) {
        var door = ColumnDoor.of(unit.params());
        var pumps = new ArrayList<ColumnComponents.Pump>();
        for (var row : unit.params().rows("pumparounds")) pumps.add(pump(row.text("pump", ColumnComponents.Pump.STANDARD.name())));
        return door.choices(pump(unit.params().text("reflux_pump", ColumnComponents.Pump.STANDARD.name())), pumps);
    }

    private static @Nullable V3ColumnDisplayResult display(SheetContext x, String id) {
        var results = x.state().shown(id);
        var report = results == null ? null : results.report(id);
        return report == null ? null : report.display();
    }

    private static List<Column.Button> tabs(SheetContext x, Page page) {
        var out = new ArrayList<Column.Button>();
        int i = 0;
        for (var tab : CalculatorColumn.tabs(page, x.t())) out.add(new Column.Button("tab/" + i++, tab.text(), tab.active(), tab.hint(), tab.latched()));
        return out;
    }

    public static Door door(SheetContext x, String id, Door.View view) {
        var unit = x.editor().unit(id);
        var door = ColumnDoor.of(unit.params());
        var page = Page.values()[view.tab];
        var values = new HashMap<String, String>();
        values(x, unit, door, values);
        var display = display(x, id);
        List<List<Column.Row>> columns;
        Door.Glass glass;
        String symbol = x.units().temperature().symbol();
        switch (page) {
            case RESULTS -> {
                var streams = display == null ? List.<String>of() : display.streams().stream().map(s -> s.streamId()).toList();
                var rows = CalculatorColumn.results(new CalculatorColumn.ResultsInput(false, view.results, view.metric, streams, view.stream, view.tray, door.stages()), x.t());
                columns = rows.two() ? List.of(rows.left(), rows.right()) : List.of(rows.right());
                glass = new ResultGlass(x, id, door, view);
            }
            case FEED -> {
                // The feed comes down its stream: the page reads it, and the stream's own door sets it.
                var stream = x.into(id, "feed");
                var feed = x.props(stream);
                var u = x.units();
                columns = List.of(List.of(Door.group(x.text("door.group.feed"), new Column.Line(stream == null ? x.text("door.none") : stream.name(), Palette.TEXT), Door.meter(c(x, "field.flow"), feed == null ? "" : u.moles(feed.moles()), u.molesUnit()),
                        Door.meter(c(x, "field.temperature"), feed == null ? "" : u.t(feed.temperature()), symbol), Door.meter(x.text("door.pressure"), feed == null ? "" : u.p(feed.pressure()), u.pressure().symbol()))));
                glass = stream == null ? (c, skin, box) -> {
                } : StreamDoors.contents(x, stream, view);
            }
            case EQUIPMENT -> {
                var pumps = new ArrayList<ColumnComponents.Pump>();
                for (var row : unit.params().rows("pumparounds")) pumps.add(pump(row.text("pump", ColumnComponents.Pump.STANDARD.name())));
                var rows = CalculatorColumn.equipment(new CalculatorColumn.EquipmentInput(false, door.condenser(), pump(unit.params().text("reflux_pump", ColumnComponents.Pump.STANDARD.name())), pumps,
                        x.host().stores() && !x.host().reader() && input(x, id) != null, door.units(), ColumnComponents.kettleRequired(door.reboilerDuty())), x.t());
                var right = new ArrayList<>(rows.right());
                right.add(right.size() - 1, Door.group(x.text("door.group.tube_sides"), Door.entry("tube", x.text("door.drop"), x.units().pressure().symbol())));
                columns = List.of(right);
                glass = connections(x, unit);
            }
            default -> {
                var in = new CalculatorColumn.ColumnInput(false, true, symbol, view.connection, view.index, view.spread(door), door.draws().size() < V3ColumnInput.MAX_SIDE_DRAWS, door.pumparounds().size() < MAX_PA, door.steam().size() < MAX_STEAM);
                var rows = CalculatorColumn.column(in, x.t());
                var left = new ArrayList<Column.Row>();
                left.add(Door.name(x.text("door.name")));
                left.add(rows.left().getFirst());
                var feed = x.props(x.into(id, "feed"));
                left.add(Door.group(x.text("door.group.feed"), Door.meter(c(x, "field.flow"), feed == null ? "" : x.units().moles(feed.moles()), x.units().molesUnit()),
                        Door.meter(c(x, "field.temperature"), feed == null ? "" : x.units().t(feed.temperature()), symbol), new Column.Field("s3", c(x, "field.tray"), "", null)));
                columns = List.of(left, wired(x, unit, rows.right(), view));
                glass = drawing(x, id, door, view);
            }
        }
        var status = status(x, id, door, display, page);
        var foot = CalculatorColumn.footerButtons(new CalculatorColumn.Footer(false, false, true, false, symbol), x.t());
        String aloneOff = aloneOff(x, id, door);
        var footer = List.of(Door.key("solve", x.text("door.solve_alone"), aloneOff == null, aloneOff), Door.key("templates", c(x, "templates"), !x.editor().locked(), x.editor().locked() ? x.text("door.locked") : null), foot.get(3), foot.get(4));
        return new Door(x.text("door.column_title"), tabs(x, page), view.tab, status.text(), status.tone(), true, columns, values, glass, footer, new Door.Actions() {
            @Override public void entry(String key, String text) {
                write(x, id, key, text);
            }

            @Override public void button(String key) {
                press(x, id, key, view);
            }
        });
    }

    /** A wired steam feed's flow and temperature are the stream's: its entries turn to meters. */
    private static List<Column.Row> wired(SheetContext x, Flowsheet.Unit unit, List<Column.Row> rows, Door.View view) {
        if (view.connection != Connection.STEAM) return rows;
        var steam = ColumnDoor.of(unit.params()).steam();
        if (view.index >= steam.size()) return rows;
        var stream = x.into(unit.id(), "steam/" + steam.get(view.index).id());
        if (stream == null) return rows;
        var props = x.props(stream);
        var out = new ArrayList<Column.Row>();
        for (var row : rows) {
            if (!(row instanceof Column.Group group) || group.rows().stream().noneMatch(r -> r instanceof Column.Field f && f.key().equals("t" + view.index + "rate"))) {
                out.add(row);
                continue;
            }
            var inner = new ArrayList<Column.Row>();
            for (var r : group.rows()) {
                if (r instanceof Column.Field f && f.key().endsWith("rate")) inner.add(Door.meter(f.label(), props == null ? "" : x.units().moles(props.moles()), x.units().molesUnit()));
                else if (r instanceof Column.Field f && f.key().endsWith("T")) inner.add(Door.meter(f.label(), props == null ? "" : x.units().t(props.temperature()), x.units().temperature().symbol()));
                else inner.add(r);
            }
            out.add(new Column.Group(group.title(), group.hint(), inner));
        }
        return out;
    }

    /** Why Solve alone is off, or null where it may go: it needs a run thread, a free sheet and the stream of every inlet the unit reads. */
    private static @Nullable String aloneOff(SheetContext x, String id, ColumnDoor door) {
        if (!x.host().canRun()) return x.text("hint.run");
        if (x.editor().locked()) return x.text("door.locked");
        if (!hasStream(x, x.into(id, "feed"))) return x.text("door.solve_no_stream");
        for (var steam : door.steam()) {
            var wired = x.into(id, "steam/" + steam.id());
            if (wired != null && !hasStream(x, wired)) return x.text("door.solve_no_stream");
        }
        return null;
    }

    /**
     * Whether {@code stream} has a state now: fed on the sheet itself, which needs no run, or left by a run. A grey line counts, as what it
     * last held: in a loop, the edit that brings a player to Solve alone greys the column's own feed.
     */
    private static boolean hasStream(SheetContext x, Flowsheet.@Nullable Stream stream) {
        if (stream == null) return false;
        if (stream.from() == null && stream.feed() != null) return stream.feed().amounts().values().stream().anyMatch(amount -> amount > 0);
        var last = x.last(stream);
        return last != null && !last.empty();
    }

    /** Applies {@code template} on the feed the column has now (decision D4), as one edit Undo takes back. With no feed it applies as written. */
    public static boolean template(SheetContext x, String id, V3ColumnTemplate template) {
        var stream = x.into(id, "feed");
        var last = x.last(stream);
        double feed = last != null ? last.total() : 0;
        if (last == null && stream != null && stream.feed() != null) for (double amount : stream.feed().amounts().values()) feed += amount;
        return x.editor().setParams(id, ColumnDoor.template(x.editor().unit(id).params(), template.scaledTo(feed * 3.6)), null);
    }

    private static ColumnComponents.Pump pump(String name) {
        try {
            return ColumnComponents.Pump.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return ColumnComponents.Pump.STANDARD;
        }
    }

    private static SheetState.Status status(SheetContext x, String id, ColumnDoor door, @Nullable V3ColumnDisplayResult display, Page page) {
        if (display == null) return new SheetState.Status(x.text("door.status.not_solved"), Palette.MUTED);
        return new SheetState.Status(switch (page) {
            case RESULTS -> display.inspection().map(v -> {
                var h = display.trayHydraulics();
                return h.isPresent() ? x.text("door.status.column_flood", SheetUnits.number(1, totalDrop(v)), SheetUnits.number(1, h.get().maximumFloodFraction() * 100), h.get().maximumFloodTray() + 1) : x.text("door.status.solved");
            }).orElse(x.text("door.status.solved"));
            case EQUIPMENT -> {
                var unit = x.editor().unit(id);
                var ports = UnitTypes.model("column").ports(unit.params());
                int free = 0;
                for (var port : ports) if ((port.direction() == Port.Direction.IN ? x.into(id, port.id()) : x.outOf(id, port.id())) == null) free++;
                yield free == 0 ? x.text("door.status.points_all", ports.size()) : x.text("door.status.points_free", ports.size(), free);
            }
            default -> x.text("door.status.column", count(x, "draw", door.draws().size()), count(x, "pumparound", door.pumparounds().size()), count(x, "steam", door.steam().size()));
        }, Palette.ACCENT);
    }

    // ---- Settings as the calculator's entries ----

    private static void values(SheetContext x, Flowsheet.Unit unit, ColumnDoor door, Map<String, String> values) {
        var u = x.units();
        values.put("name", unit.name());
        values.put("s2", Integer.toString(door.stages() + 2));
        values.put("s3", Integer.toString(door.feedStage() + 1));
        values.put("s4", u.t(door.condenserTemperature()));
        values.put("s5", SheetUnits.megawatts(door.reboilerDuty()));
        values.put("s6", SheetUnits.number(2, door.reflux()));
        values.put("s7", u.p(door.topPressure()));
        values.put("s9", SheetUnits.number(2, door.diameter()));
        values.put("tube", u.p(door.condenserDrop()));
        for (int i = 0; i < door.draws().size(); i++) {
            var d = door.draws().get(i);
            values.put("d" + i + "rate", SheetUnits.number(1, d.flow() * KMOL));
            values.put("d" + i + "stage", Integer.toString(d.tray() + 1));
        }
        for (int i = 0; i < door.pumparounds().size(); i++) {
            var p = door.pumparounds().get(i);
            values.put("c" + i + "duty", SheetUnits.megawatts(-p.duty()));
            values.put("c" + i + "draw", Integer.toString(p.drawTray() + 1));
            values.put("c" + i + "return", Integer.toString(p.returnTray() + 1));
        }
        for (int i = 0; i < door.steam().size(); i++) {
            var s = door.steam().get(i);
            values.put("t" + i + "rate", SheetUnits.number(1, s.flow() * KMOL));
            values.put("t" + i + "stage", Integer.toString(s.stage() + 1));
            values.put("t" + i + "T", u.t(s.temperature()));
        }
    }

    private static JsonObject copy(SheetContext x, String id) {
        return x.editor().unit(id).params().json().deepCopy();
    }

    private static void row(SheetContext x, String id, String rows, int i, String field, double value, String step) {
        var json = copy(x, id);
        json.getAsJsonArray(rows).get(i).getAsJsonObject().addProperty(field, value);
        x.editor().setParams(id, new Params(json), step);
    }

    private static void write(SheetContext x, String id, String key, String text) {
        String step = "entry " + id + " " + key;
        if (key.equals("name")) {
            x.editor().rename(id, text);
            return;
        }
        var value = SheetContext.number(text);
        if (value == null) return;
        var u = x.units();
        var editor = x.editor();
        double kelvin = u.temperature().si(value), pascal = u.pressure().si(value);
        switch (key) {
            case "s2" -> editor.set(id, "stages", value - 2, step);
            case "s3" -> editor.set(id, "feed_stage", value - 1, step);
            case "s4" -> editor.set(id, "condenser_temperature", kelvin, step);
            case "s5" -> editor.set(id, "reboiler_duty", value * 1e6, step);
            case "s6" -> editor.set(id, "reflux", value, step);
            case "s7" -> editor.set(id, "top_pressure", pascal, step);
            case "s9" -> editor.set(id, "diameter", value, step);
            case "tube" -> {
                var json = copy(x, id);
                json.addProperty("condenser_drop", pascal);
                if (json.has("pumparounds")) for (var row : json.getAsJsonArray("pumparounds")) row.getAsJsonObject().addProperty("drop", pascal);
                editor.setParams(id, new Params(json), step);
            }
            default -> {
                int i = Character.getNumericValue(key.charAt(1));
                String field = key.substring(2);
                switch (key.charAt(0)) {
                    case 'd' -> row(x, id, "draws", i, field.equals("rate") ? "flow" : "tray", field.equals("rate") ? value / KMOL : value - 1, step);
                    case 't' -> row(x, id, "steam", i, field.equals("rate") ? "flow" : field.equals("T") ? "temperature" : "stage", field.equals("rate") ? value / KMOL : field.equals("T") ? kelvin : value - 1, step);
                    case 'c' -> row(x, id, "pumparounds", i, switch (field) {
                        case "duty" -> "duty";
                        case "draw" -> "draw_tray";
                        default -> "return_tray";
                    }, field.equals("duty") ? -value * 1e6 : value - 1, step);
                    default -> {
                    }
                }
            }
        }
    }

    private static JsonArray rows(JsonObject json, String key) {
        if (!json.has(key)) json.add(key, new JsonArray());
        return json.getAsJsonArray(key);
    }

    private static void press(SheetContext x, String id, String key, Door.View view) {
        var editor = x.editor();
        int colon = key.indexOf(':');
        String head = colon < 0 ? key : key.substring(0, colon), tail = colon < 0 ? "" : key.substring(colon + 1);
        if (key.startsWith("phase/")) view.phase = Integer.parseInt(key.substring(6));
        if (key.startsWith("tab/")) {
            view.tab = Integer.parseInt(key.substring(4));
            return;
        }
        switch (head) {
            case "view" -> {
                for (var v : CalculatorColumn.View.values()) if (v.key().equals(tail)) view.results = v;
            }
            case "metric" -> {
                for (var m : Metric.values()) if (m.key().equals(tail)) view.metric = m;
            }
            case "stream" -> view.stream = Integer.parseInt(tail);
            case "tray-" -> view.tray = Math.max(0, view.tray - 1);
            case "tray+" -> view.tray = Math.min(ColumnDoor.of(editor.unit(id).params()).stages() + 1, view.tray + 1);
            case "tray" -> view.tray = Integer.parseInt(tail);
            case "draw" -> select(view, Connection.DRAW, Integer.parseInt(tail));
            case "pa" -> select(view, Connection.PA, Integer.parseInt(tail));
            case "steam" -> select(view, Connection.STEAM, Integer.parseInt(tail));
            case "add" -> add(x, id, tail, view);
            case "remove" -> remove(x, id, view);
            case "split" -> row(x, id, view, "split", tail.equals("uniform") ? "UNIFORM" : "RETURN_TRAY");
            case "condenser" -> editor.set(id, "condenser", (tail.equals("exchanger") ? ColumnComponents.Condenser.EXCHANGER : ColumnComponents.Condenser.AIR_COOLER).name(), null);
            case "reflux" -> editor.set(id, "reflux_pump", tail.toUpperCase(Locale.ROOT), null);
            default -> {
                if (head.startsWith("units/") && (head.endsWith("-") || head.endsWith("+"))) units(x, id, head.substring(6, head.length() - 1), head.endsWith("+") ? 1 : -1);
                else if (head.startsWith("pa")) {
                    var json = copy(x, id);
                    json.getAsJsonArray("pumparounds").get(Integer.parseInt(head.substring(2))).getAsJsonObject().addProperty("pump", tail.toUpperCase(Locale.ROOT));
                    editor.setParams(id, new Params(json), null);
                }
            }
        }
    }

    /**
     * Steps a member count by {@code by}, within 1 to {@link ColumnComponents#MAX_UNITS}: {@code condenser} and {@code kettle}
     * on the door, {@code pa<i>} on pumparound row {@code i}. One undo step each.
     */
    private static void units(SheetContext x, String id, String member, int by) {
        var door = ColumnDoor.of(x.editor().unit(id).params());
        var json = copy(x, id);
        String step = "units " + id + " " + member;
        switch (member) {
            case "condenser" -> json.addProperty(ColumnDoor.CONDENSER_UNITS, Math.clamp(door.units().condenser() + by, 1, ColumnComponents.MAX_UNITS));
            case "kettle" -> json.addProperty(ColumnDoor.KETTLE_UNITS, Math.clamp(door.units().kettle() + by, 1, ColumnComponents.MAX_UNITS));
            default -> {
                int i = Integer.parseInt(member.substring(2));
                json.getAsJsonArray("pumparounds").get(i).getAsJsonObject().addProperty(ColumnDoor.UNITS, Math.clamp(door.units().cooler(i) + by, 1, ColumnComponents.MAX_UNITS));
            }
        }
        x.editor().setParams(id, new Params(json), step);
    }

    private static void select(Door.View view, Connection connection, int index) {
        view.connection = connection;
        view.index = index;
    }

    private static void row(SheetContext x, String id, Door.View view, String field, String value) {
        var json = copy(x, id);
        json.getAsJsonArray("pumparounds").get(view.index).getAsJsonObject().addProperty(field, value);
        x.editor().setParams(id, new Params(json), null);
    }

    /** A new row, by the calculator's limits, on the tray in hand; its id never one a stream still names. */
    private static void add(SheetContext x, String id, String kind, Door.View view) {
        var json = copy(x, id);
        var door = ColumnDoor.of(x.editor().unit(id).params());
        int n = door.stages(), node = Math.clamp(view.tray, 1, Math.max(1, n));
        var row = new JsonObject();
        row.addProperty("id", x.editor().rowId(id, "draws", "steam", "pumparounds"));
        switch (kind) {
            case "draw" -> {
                row.addProperty("tray", node);
                row.addProperty("flow", 100 / KMOL);
                rows(json, "draws").add(row);
                select(view, Connection.DRAW, door.draws().size());
            }
            case "pa" -> {
                row.addProperty("return_tray", Math.max(1, node - 2));
                row.addProperty("draw_tray", Math.max(2, node));
                row.addProperty("duty", -1e7);
                row.addProperty("split", "RETURN_TRAY");
                row.addProperty("drop", door.condenserDrop());
                rows(json, "pumparounds").add(row);
                select(view, Connection.PA, door.pumparounds().size());
            }
            default -> {
                row.addProperty("stage", n + 1);
                row.addProperty("flow", 100 / KMOL);
                row.addProperty("temperature", 533.15);
                rows(json, "steam").add(row);
                select(view, Connection.STEAM, door.steam().size());
            }
        }
        x.editor().setParams(id, new Params(json), null);
    }

    private static void remove(SheetContext x, String id, Door.View view) {
        var json = copy(x, id);
        String key = switch (view.connection) {
            case DRAW -> "draws";
            case PA -> "pumparounds";
            case STEAM -> "steam";
            case NONE -> "";
        };
        if (key.isEmpty()) return;
        rows(json, key).remove(view.index);
        view.connection = Connection.NONE;
        view.index = 0;
        x.editor().setParams(id, new Params(json), null);
    }

    // ---- The glass ----

    private static Door.Glass drawing(SheetContext x, String id, ColumnDoor door, Door.View view) {
        return new Door.Glass() {
            private CalculatorColumn.Drawing placed(Rect box) {
                int n = door.stages();
                var draws = new ArrayList<Mark>();
                var pas = new ArrayList<Mark>();
                var steams = new ArrayList<Mark>();
                for (int i = 0; i < door.draws().size(); i++) draws.add(new Mark(i, door.draws().get(i).tray(), 0));
                for (int i = 0; i < door.pumparounds().size(); i++) pas.add(new Mark(i, door.pumparounds().get(i).drawTray(), door.pumparounds().get(i).returnTray()));
                for (int i = 0; i < door.steam().size(); i++) steams.add(new Mark(i, door.steam().get(i).stage(), 0));
                var display = display(x, id);
                var inspection = display == null ? null : display.inspection().orElse(null);
                double[] shares = null;
                if (inspection != null && inspection.nodes().size() == n + 2) {
                    double min = inspection.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).min().orElse(0), max = inspection.nodes().stream().mapToDouble(V3ColumnInspection.Node::temperatureKelvin).max().orElse(1);
                    shares = new double[n + 2];
                    for (int i = 0; i < n + 2; i++) shares[i] = (inspection.nodes().get(i).temperatureKelvin() - min) / Math.max(1e-9, max - min);
                }
                var in = new CalculatorColumn.DrawingInput(n, door.feedStage(), draws, pas, steams, Math.clamp(view.tray, 0, n + 1), view.connection, view.index, shares);
                return CalculatorColumn.drawing(in, box, 0, x.m(), x.t(), node -> "");
            }

            @Override public void draw(Canvas c, Skin skin, Rect box) {
                c.clip(box);
                CalculatorColumn.draw(c, placed(box), x.t());
                c.unclip();
            }

            @Override public @Nullable String click(Rect box, double px, double py) {
                var hit = placed(box).at(px, py);
                return hit == null ? null : hit.action();
            }
        };
    }

    private static String count(SheetContext x, String word, int n) {
        return x.text("door.count." + word + (n == 1 ? "" : "s"), n);
    }

    /** A connection point's place in the list: what goes in, the pairs, what comes out. */
    private static int rank(String port) {
        return port.equals("feed") ? 0 : port.startsWith("steam/") ? 1 : port.startsWith("condenser") ? 2 : port.startsWith("reboiler") ? 3 : port.startsWith("pa/") ? 4 : port.equals("overhead") ? 5 : port.equals("distillate") ? 6
                : port.equals("water") ? 7 : port.startsWith("draw/") ? 8 : 9;
    }

    /** A connection point's name, with its tray where it has one. */
    private static String point(SheetContext x, ColumnDoor door, String port) {
        int tray = port.equals("feed") ? door.feedStage() : -1;
        for (var draw : door.draws()) if (port.equals("draw/" + draw.id())) tray = draw.tray();
        for (var steam : door.steam()) if (port.equals("steam/" + steam.id())) tray = steam.stage();
        String name = StreamDoors.portName(x, port);
        return tray < 0 ? name : x.text("door.port_tray", name, tray + 1);
    }

    /** The block's connection points: what each carries, read from the last run. */
    private static Door.Glass connections(SheetContext x, Flowsheet.Unit unit) {
        return (c, skin, box) -> {
            var u = x.units();
            int x0 = box.x() + 4, y = box.y() + 4;
            String[] head = {x.text("door.connection"), x.text("door.stream"), x.text("door.temperature"), x.text("door.heat")};
            int[] at = {0, 124, 200, 280};
            for (int i = 0; i < head.length; i++) c.text(head[i], x0 + at[i], y, Palette.MUTED);
            c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
            y += 14;
            var results = x.state().shown(unit.id());
            int r = 0;
            var door = ColumnDoor.of(unit.params());
            var ports = new ArrayList<>(UnitTypes.model("column").ports(unit.params()));
            ports.sort(java.util.Comparator.comparingInt(port -> rank(port.id())));
            for (var port : ports) {
                var stream = port.direction() == Port.Direction.IN ? x.into(unit.id(), port.id()) : x.outOf(unit.id(), port.id());
                var props = x.props(stream);
                if (r % 2 == 1) c.fill(box.x(), y - 3, box.right(), y + 11, DialogColumns.BAND);
                c.text(point(x, door, port.id()), x0 + at[0], y, Palette.TEXT);
                c.text(stream == null ? x.text("door.none") : stream.name(), x0 + at[1], y, Palette.MUTED);
                if (props != null) c.text(u.t(props.temperature()) + " " + u.temperature().symbol(), x0 + at[2], y, Palette.ACCENT);
                double heat = results == null ? Double.NaN : heat(results, unit, port.id());
                if (!Double.isNaN(heat)) c.text(SheetUnits.megawatts(Math.abs(heat)) + " MW", x0 + at[3], y, Palette.ACCENT);
                r++;
                y += 14;
            }
        };
    }

    /** The heat of a pair, shown on its inlet: a part's reading, {@code T-101/pa 1}. */
    private static double heat(SheetResults results, Flowsheet.Unit unit, String port) {
        if (port.equals("condenser_in")) return results.reading(unit.id(), "condenser");
        if (port.equals("reboiler_in")) return results.reading(unit.id(), "reboiler");
        if (port.startsWith("pa/") && port.endsWith("/in")) return results.part(unit.id(), "pa " + port.split("/")[1], "duty");
        return Double.NaN;
    }

    private static double drop(V3ColumnInspection view, int tray) {
        return (view.nodes().get(tray).pressurePascal() - view.nodes().get(tray - 1).pressurePascal()) / 1000;
    }

    private static double totalDrop(V3ColumnInspection view) {
        int n = view.input().stageCount();
        return n < 2 ? 0 : (view.nodes().get(n).pressurePascal() - view.nodes().get(1).pressurePascal()) / 1000;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String percent(double value) {
        return value != 0 && Math.abs(value) < .05 ? String.format(Locale.ROOT, "%.2g", value) : fmt(value);
    }

    private static String fraction(double value) {
        return String.format(Locale.ROOT, "%.5g", value);
    }

    /** The Results page's glass: the calculator's profiles, products, heat, tray and solver sheets. */
    private record ResultGlass(SheetContext x, String id, ColumnDoor door, Door.View view) implements Door.Glass {
        @Override public void draw(Canvas c, Skin skin, Rect box) {
            var m = c.measure();
            int width = box.w() - ScrollView.BAR - 2;
            var display = display(x, id);
            var inspection = display == null ? null : display.inspection().orElse(null);
            c.clip(box);
            if (view.results == CalculatorColumn.View.PROFILES) {
                if (inspection == null) sheet(c, box, new CalculatorColumn.SheetBuilder(width, m).note(c(x, "solve_profiles"), Palette.MUTED));
                else profile(c, box, inspection);
            } else sheet(c, box, switch (view.results) {
                case PRODUCTS -> products(display, width, c);
                case HEAT -> heat(display, width, c);
                case TRAY -> tray(inspection, width, c);
                default -> solver(display, inspection, width, c);
            });
            c.unclip();
        }

        private void sheet(Canvas c, Rect box, CalculatorColumn.SheetBuilder builder) {
            CalculatorColumn.draw(c, builder.build(), new Rect(box.x(), box.y(), box.w() - ScrollView.BAR - 2, box.h()), 0);
        }

        private double value(V3ColumnInspection.Node node, int series) {
            return switch (view.metric) {
                case TEMPERATURE -> x.units().temperature().display(node.temperatureKelvin());
                case PRESSURE -> node.pressurePascal() / 1000;
                case TRAFFIC -> switch (series) {
                    case 0 -> node.liquidMolPerSecond() * KMOL;
                    case 1 -> node.vaporMolPerSecond() * KMOL;
                    case 2 -> node.waterVaporMolPerSecond() * KMOL;
                    default -> node.freeWaterMolPerSecond() * KMOL;
                };
                case DROP -> throw new IllegalStateException("A drop is a bar, not a profile");
            };
        }

        private void profile(Canvas c, Rect box, V3ColumnInspection inspection) {
            var m = c.measure();
            int nodes = inspection.nodes().size();
            if (view.metric == Metric.DROP) {
                int head = CalculatorColumn.heading(c, box, c(x, "column_dp", fmt(totalDrop(inspection))), List.of(), m);
                var values = new double[nodes];
                for (int i = 2; i <= inspection.input().stageCount(); i++) values[i] = drop(inspection, i);
                CalculatorColumn.drops(c, CalculatorColumn.plot(box, head), values);
                return;
            }
            boolean traffic = view.metric == Metric.TRAFFIC;
            var legend = !traffic ? List.<CalculatorColumn.Legend>of() : List.of(new CalculatorColumn.Legend(c(x, "traffic.organic"), CalculatorColumn.TRAFFIC[0]), new CalculatorColumn.Legend(c(x, "traffic.vapor"), CalculatorColumn.TRAFFIC[1]),
                    new CalculatorColumn.Legend(c(x, "traffic.water"), CalculatorColumn.TRAFFIC[2]), new CalculatorColumn.Legend(c(x, "traffic.aqueous"), CalculatorColumn.TRAFFIC[3]));
            String title = switch (view.metric) {
                case TEMPERATURE -> c(x, "temperature", x.units().temperature().symbol());
                case PRESSURE -> c(x, "pressure");
                default -> c(x, "traffic");
            };
            int head = CalculatorColumn.heading(c, box, title, legend, m);
            var plot = CalculatorColumn.plot(box, head);
            if (plot.h() < 12) return;
            int series = traffic ? 4 : 1;
            var values = new double[series][nodes];
            for (int s = 0; s < series; s++) for (int i = 0; i < nodes; i++) values[s][i] = value(inspection.nodes().get(i), s);
            if (!CalculatorColumn.profile(c, plot, values, traffic ? CalculatorColumn.TRAFFIC : new int[]{Palette.ACCENT}))
                CalculatorColumn.draw(c, new CalculatorColumn.SheetBuilder(box.w(), m).note(c(x, "absent_phase"), Palette.WARN).build(), new Rect(box.x(), box.y() + head, box.w(), box.h() - head), 0);
        }

        private String material(String id) {
            return x.fluids().names().apply(id);
        }

        private CalculatorColumn.SheetBuilder products(@Nullable V3ColumnDisplayResult display, int width, Canvas c) {
            var m = c.measure();
            var out = new CalculatorColumn.SheetBuilder(width, m);
            if (display == null || display.streams().isEmpty()) return out.note(c(x, "solve_products"), Palette.MUTED);
            var stream = display.streams().get(Math.clamp(view.stream, 0, display.streams().size() - 1));
            var u = x.units();
            out.pair(c(x, "field.flow"), fmt(stream.molarFlowMolPerSecond() * KMOL) + " kmol/h").pair(c(x, "reading.mass_flow"), fmt(stream.massFlowKgPerSecond() * 3600) + " kg/h")
                    .pair(c(x, "field.temperature"), u.t(stream.temperatureKelvin()) + " " + u.temperature().symbol()).pair(c(x, "reading.pressure"), fmt(stream.pressurePascal() / 1000) + " kPa").blank();
            List<String> header = List.of(c(x, "component"), c(x, "mole_percent"), c(x, "molar_rate"), c(x, "mass_percent"), c(x, "mass_rate"));
            var rows = new ArrayList<List<String>>();
            for (var part : stream.moleFractions())
                rows.add(List.of(material(part.componentId()), percent(part.moleFraction() * 100), percent(stream.molarFlowMolPerSecond() * part.moleFraction() * KMOL), percent(part.massFraction() * 100), percent(stream.massFlowKgPerSecond() * part.massFraction() * 3600)));
            if (CalculatorColumn.SheetBuilder.fits(header, rows, width - 80 + m.width(header.getFirst()), m)) return out.table(header, rows);
            return out.table(header.subList(0, 3), rows.stream().map(r -> r.subList(0, 3)).toList());
        }

        private CalculatorColumn.SheetBuilder heat(@Nullable V3ColumnDisplayResult display, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure()).note(c(x, "signed_heat"), Palette.MUTED);
            if (display != null) display.dutyLedger().ifPresent(d -> {
                out.note(c(x, "tray_duty", 1, fmt(d.condenserWatts() / 1e6)), Palette.TEXT).note(c(x, "tray_duty", door.stages() + 2, fmt(d.reboilerWatts() / 1e6)), Palette.TEXT)
                        .note(c(x, "stage_heat", fmt(d.stageHeatTotalWatts() / 1e6)), Palette.TEXT).note(c(x, "feed_enthalpy", fmt(d.feedEnthalpyWatts() / 1e6)), Palette.TEXT).note(c(x, "steam_enthalpy", fmt(d.steamEnthalpyWatts() / 1e6)), Palette.TEXT);
                for (var stage : d.stageDuties()) out.note(c(x, "tray_duty", stage.trayNumber() + 1, fmt(stage.dutyWatts() / 1e6)), Palette.TEXT);
            });
            return out;
        }

        private CalculatorColumn.SheetBuilder tray(@Nullable V3ColumnInspection inspection, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure());
            int n = door.stages(), selected = Math.clamp(view.tray, 0, n + 1);
            out.note(c(x, selected == 0 ? "top_tray" : selected == n + 1 ? "bottom_tray" : "tray", selected + 1), Palette.TEXT);
            if (inspection == null || selected >= inspection.nodes().size()) return out.note(c(x, "solve_to_inspect"), Palette.MUTED);
            var node = inspection.nodes().get(selected);
            var u = x.units();
            out.pair(c(x, "field.temperature"), u.t(node.temperatureKelvin()) + " " + u.temperature().symbol()).pair(c(x, "reading.pressure"), fmt(node.pressurePascal() / 1000) + " kPa")
                    .pair(c(x, "tray_dp"), selected > 0 && selected <= n ? fmt(drop(inspection, selected)) + " kPa" : "—").pair(c(x, "hc_liquid"), fmt(node.liquidMolPerSecond() * KMOL) + " kmol/h")
                    .pair(c(x, "hc_vapor"), fmt(node.vaporMolPerSecond() * KMOL) + " kmol/h").pair(c(x, "water_vapor"), fmt(node.waterVaporMolPerSecond() * KMOL) + " kmol/h").pair(c(x, "free_water"), fmt(node.freeWaterMolPerSecond() * KMOL) + " kmol/h").blank();
            var rows = new ArrayList<List<String>>();
            var basis = inspection.input().componentBasis();
            for (int i = 0; i < basis.componentCount(); i++) rows.add(List.of(material(basis.componentId(i)), node.liquidMolPerSecond() == 0 ? "—" : percent(node.liquidFractions().get(i) * 100), node.vaporMolPerSecond() == 0 ? "—" : percent(node.vaporFractions().get(i) * 100)));
            return out.table(List.of(c(x, "component"), c(x, "liquid_percent"), c(x, "vapor_percent")), rows);
        }

        private CalculatorColumn.SheetBuilder solver(@Nullable V3ColumnDisplayResult display, @Nullable V3ColumnInspection inspection, int width, Canvas c) {
            var out = new CalculatorColumn.SheetBuilder(width, c.measure());
            if (display == null) return out.note(c(x, "solve_profiles"), Palette.MUTED);
            if (inspection != null) {
                var wet = new ArrayList<String>();
                for (int i = 1; i <= inspection.input().stageCount(); i++) if (inspection.nodes().get(i).freeWaterMolPerSecond() > 0) wet.add(Integer.toString(i + 1));
                out.note(c(x, "wet_trays", wet.isEmpty() ? c(x, "none") : String.join(", ", wet)), Palette.TEXT).note(c(x, "column_dp", fmt(totalDrop(inspection))), Palette.TEXT);
                display.trayHydraulics().ifPresent(h -> out.note(c(x, h.floods() ? "flooding" : "below_flood", h.maximumFloodTray() + 1), h.floods() ? Palette.WARN : Palette.TEXT).note(c(x, "worst_flood", fmt(h.maximumFloodFraction() * 100)), Palette.TEXT));
            }
            out.note(c(x, "iterations", display.newtonIterations()), Palette.TEXT).note(c(x, "residual", fraction(display.maximumScaledResidual())), Palette.TEXT).note(c(x, "closure", fraction(display.closureTolerance())), Palette.TEXT);
            display.inspection().ifPresent(v -> {
                for (var check : v.audit().checks()) out.note(c(x, "audit_value", check.family(), fraction(check.value()), fraction(check.limit())), Palette.TEXT);
            });
            return out;
        }
    }
}

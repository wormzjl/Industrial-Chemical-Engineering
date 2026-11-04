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
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    private ColumnDoors() {
    }

    static String c(SheetContext x, String key, Object... args) {
        return x.t().get(KEY + key, args);
    }

    /**
     * The column as its door stands, on its feed: what Save design and a template take of it, with no solve. Null with no feed yet, or with a door {@code V3ColumnInput} refuses, whose
     * Run says why. A feed's parts are what its line last held, or what the sheet sets.
     */
    public static @Nullable V3ColumnInput input(SheetContext x, String id) {
        return input(x, id, ColumnDoor.of(x.editor().unit(id).params()));
    }

    private static @Nullable V3ColumnInput input(SheetContext x, String id, ColumnDoor door) {
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
            return door.input(x.fluids().pack(), flows, temperature);
        } catch (IllegalArgumentException refused) {
            return null;
        }
    }

    /** What the Equipment page has chosen. The design names no pump kind (batch 2026-11-03-rotating-machinery, D42): its pumps are standard. */
    public static ColumnComponents.Choices choices(Flowsheet.Unit unit) {
        return ColumnDoor.of(unit.params()).choices();
    }

    /**
     * The pumps a built column's design names, as its schematic draws them: an assembly's door holds them ({@code AssemblyBlock}, read
     * unchanged from the column's shape, where a design saved before D42 may name an in-line pump); a sheet's door holds none, so
     * its pumps are standard.
     */
    public static ColumnComponents.Choices built(Flowsheet.Unit unit) {
        var pumps = new ArrayList<ColumnComponents.Pump>();
        for (var row : unit.params().rows("pumparounds")) pumps.add(pump(row.text("pump", ColumnComponents.Pump.STANDARD.name())));
        var choices = choices(unit).withRefluxPump(pump(unit.params().text("reflux_pump", ColumnComponents.Pump.STANDARD.name())));
        for (int i = 0; i < Math.min(pumps.size(), choices.pumparoundPumps().size()); i++) choices = choices.withPumparoundPump(i, pumps.get(i));
        return choices;
    }

    private static ColumnComponents.Pump pump(String name) {
        try {
            return ColumnComponents.Pump.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return ColumnComponents.Pump.STANDARD;
        }
    }

    /**
     * The design's pumps as the sheet's last rating gives them (W8, D43): the reflux pump, then each pumparound's, from the block's members
     * {@code reflux pump} and {@code pa <id> pump}. Before a steady run has been rated, each reads unknown.
     */
    static List<CalculatorColumn.PumpDuty> duties(SheetContext x, String id, ColumnDoor door) {
        var sized = SizingPage.sized(x);
        var out = new ArrayList<CalculatorColumn.PumpDuty>();
        out.add(duty(sized, SheetSizing.member(id, "reflux pump"), c(x, "component_reflux_pump")));
        for (int i = 0; i < door.pumparounds().size(); i++)
            out.add(duty(sized, SheetSizing.member(id, SheetSizing.PumparoundMember.name(door.pumparounds().get(i).id(), "pump")), c(x, "component_pa", i + 1)));
        return out;
    }

    private static CalculatorColumn.PumpDuty duty(SheetSizing.@Nullable Sized sized, String member, String name) {
        var unit = sized == null ? null : sized.units().get(member);
        var duty = unit == null ? null : PumpSizing.Duty.of(unit.rating());
        return duty == null ? CalculatorColumn.PumpDuty.unknown(name) : new CalculatorColumn.PumpDuty(name, duty.flow(), duty.head(), duty.shaft(), duty.npsh(), duty.motor());
    }

    static @Nullable V3ColumnDisplayResult display(SheetContext x, String id) {
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
                glass = new ColumnGlass.ResultGlass(x, id, door, view);
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
                var rows = CalculatorColumn.equipment(new CalculatorColumn.EquipmentInput(false, door.condenser(), door.pumparounds().size(),
                        x.host().stores() && !x.host().reader() && input(x, id, door) != null, door.units(), ColumnComponents.kettleRequired(door.reboilerDuty())), x.t());
                var right = new ArrayList<>(rows.right());
                right.add(right.size() - 1, Door.group(x.text("door.group.tube_sides"), Door.entry("tube", x.text("door.drop"), x.units().pressure().symbol())));
                // The ejector on the vapour line (W10), where the top is under ambient pressure: the ejector tile's two pressures.
                if (ColumnComponents.ejectorRequired(door.topPressure()))
                    right.add(right.size() - 1, Door.group(x.text("door.group.ejector"), Door.entry("ejector_discharge", x.text("door.discharge"), x.units().pressure().symbol()),
                            Door.entry("ejector_motive", x.text("door.motive"), x.units().pressure().symbol())));
                columns = List.of(right);
                glass = ColumnGlass.connections(x, unit, door, duties(x, id, door));
            }
            default -> {
                var in = new CalculatorColumn.ColumnInput(false, true, symbol, x.units().pressure().symbol(), x.units().molesUnit(), view.connection, view.index, view.spread(door), door.draws().size() < V3ColumnInput.MAX_SIDE_DRAWS, door.pumparounds().size() < MAX_PA, door.steam().size() < MAX_STEAM);
                var rows = CalculatorColumn.column(in, x.t());
                var left = new ArrayList<Column.Row>();
                left.add(Door.name(x.text("door.name")));
                left.add(rows.left().getFirst());
                var feed = x.props(x.into(id, "feed"));
                left.add(Door.group(x.text("door.group.feed"), Door.meter(c(x, "field.flow"), feed == null ? "" : x.units().moles(feed.moles()), x.units().molesUnit()),
                        Door.meter(c(x, "field.temperature"), feed == null ? "" : x.units().t(feed.temperature()), symbol), new Column.Field("s3", c(x, "field.tray"), "", null)));
                columns = List.of(left, wired(x, unit, door, rows.right(), view));
                glass = ColumnGlass.drawing(x, id, door, view);
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
    private static List<Column.Row> wired(SheetContext x, Flowsheet.Unit unit, ColumnDoor door, List<Column.Row> rows, Door.View view) {
        if (view.connection != Connection.STEAM) return rows;
        var steam = door.steam();
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
        return x.editor().setParams(id, ColumnDoor.template(x.editor().unit(id).params(), template.scaledTo(feed / SheetUnits.KMOL_PER_HOUR)), null);
    }

    private static SheetState.Status status(SheetContext x, String id, ColumnDoor door, @Nullable V3ColumnDisplayResult display, Page page) {
        if (display == null) return new SheetState.Status(x.text("door.status.not_solved"), Palette.MUTED);
        return new SheetState.Status(switch (page) {
            case RESULTS -> display.inspection().map(v -> {
                var h = display.trayHydraulics();
                return h.isPresent() ? x.text("door.status.column_flood", x.units().drop(ColumnGlass.totalDrop(v)), x.units().pressure().symbol(), SheetUnits.number(1, h.get().maximumFloodFraction() * 100), h.get().maximumFloodTray() + 1) : x.text("door.status.solved");
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

    private static String count(SheetContext x, String word, int n) {
        return x.text("door.count." + word + (n == 1 ? "" : "s"), n);
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
        values.put("ejector_discharge", u.p(door.ejector().discharge()));
        values.put("ejector_motive", u.p(door.ejector().motive()));
        for (int i = 0; i < door.draws().size(); i++) {
            var d = door.draws().get(i);
            values.put("d" + i + "rate", u.moles(d.flow()));
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
            values.put("t" + i + "rate", u.moles(s.flow()));
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
            case "ejector_discharge" -> editor.set(id, ColumnDoor.EJECTOR_DISCHARGE, pascal, step);
            case "ejector_motive" -> editor.set(id, ColumnDoor.EJECTOR_MOTIVE, pascal, step);
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
                    case 'd' -> row(x, id, "draws", i, field.equals("rate") ? "flow" : "tray", field.equals("rate") ? u.molesPerSecond(value) : value - 1, step);
                    case 't' -> row(x, id, "steam", i, field.equals("rate") ? "flow" : field.equals("T") ? "temperature" : "stage", field.equals("rate") ? u.molesPerSecond(value) : field.equals("T") ? kelvin : value - 1, step);
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
        if (view.press(key)) return;
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
            case "condenser" -> editor.set(id, "condenser", (switch (tail) {
                case "exchanger" -> ColumnComponents.Condenser.EXCHANGER;
                case "air_then_water" -> ColumnComponents.Condenser.AIR_THEN_WATER;
                default -> ColumnComponents.Condenser.AIR_COOLER;
            }).name(), null);
            default -> {
                if (head.startsWith("units/") && (head.endsWith("-") || head.endsWith("+"))) units(x, id, head.substring(6, head.length() - 1), head.endsWith("+") ? 1 : -1);
            }
        }
    }

    /**
     * Steps a member count by {@code by}, within 1 to {@link ColumnComponents#MAX_UNITS}: {@code condenser}, {@code trim} (an air-then-water condenser's water shells) and {@code kettle}
     * on the door, {@code pa<i>} on pumparound row {@code i}. One undo step each.
     */
    private static void units(SheetContext x, String id, String member, int by) {
        var door = ColumnDoor.of(x.editor().unit(id).params());
        var json = copy(x, id);
        String step = "units " + id + " " + member;
        switch (member) {
            case "condenser" -> json.addProperty(ColumnDoor.CONDENSER_UNITS, Math.clamp(door.units().condenser() + by, 1, ColumnComponents.MAX_UNITS));
            case "kettle" -> json.addProperty(ColumnDoor.KETTLE_UNITS, Math.clamp(door.units().kettle() + by, 1, ColumnComponents.MAX_UNITS));
            case "trim" -> json.addProperty(ColumnDoor.TRIM_UNITS, Math.clamp(door.units().trim() + by, 1, ColumnComponents.MAX_UNITS));
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
                row.addProperty("flow", 100 * SheetUnits.KMOL_PER_HOUR);
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
                row.addProperty("flow", 100 * SheetUnits.KMOL_PER_HOUR);
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

}

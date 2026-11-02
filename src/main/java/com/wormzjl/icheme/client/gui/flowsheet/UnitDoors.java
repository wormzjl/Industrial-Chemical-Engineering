package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram;
import com.wormzjl.icheme.client.gui.common.DialogColumns;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatFlowsheet;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Params;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's door as rows (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 5 and sheet 11): its name, targets and set points, drops and
 * readings on the door, the shipped drawing or a sketch and its streams on the glass. Tabs Design and Streams. Settings read and write the file's SI
 * values under their stable keys; what an entry shows follows the display keys. A key {@code set/<field>/<value>} sets a text setting.
 */
public final class UnitDoors {
    /** How an entry reads a setting. */
    private enum Quantity {
        PRESSURE, TEMPERATURE, MEGAWATT, PLAIN
    }

    private UnitDoors() {
    }

    /** One door's rows as they are built: each entry shows its setting and writes it back. */
    private static final class Rows {
        final SheetContext x;
        final Flowsheet.Unit unit;
        final List<Column.Row> rows = new ArrayList<>();
        final Map<String, String> values = new HashMap<>();
        final Map<String, Quantity> entries = new HashMap<>();

        Rows(SheetContext x, Flowsheet.Unit unit) {
            this.x = x;
            this.unit = unit;
            rows.add(Door.name(x.text("door.name")));
            values.put("name", unit.name());
        }

        String text(String field, String fallback) {
            try {
                return unit.params().text(field, fallback);
            } catch (FlowsheetFailure wrongKind) {
                return fallback;
            }
        }

        double number(String field) {
            try {
                return unit.params().number(field, Double.NaN);
            } catch (FlowsheetFailure wrongKind) {
                return Double.NaN;
            }
        }

        Column.Field entry(String field, String label, Quantity quantity) {
            double value = number(field);
            var u = x.units();
            entries.put(field, quantity);
            values.put(field, Double.isNaN(value) ? "" : switch (quantity) {
                case PRESSURE -> u.p(value);
                case TEMPERATURE -> u.t(value);
                case MEGAWATT -> SheetUnits.megawatts(value);
                case PLAIN -> trim(value);
            });
            return Door.entry(field, label, switch (quantity) {
                case PRESSURE -> u.pressure().symbol();
                case TEMPERATURE -> u.temperature().symbol();
                case MEGAWATT -> "MW";
                case PLAIN -> field.equals("area") ? "m²" : "";
            });
        }

        Column.Buttons choice(String field, String fallback, String... options) {
            var keys = new ArrayList<Column.Button>();
            String now = text(field, fallback);
            for (var option : options) keys.add(Door.position("set/" + field + "/" + option, x.text("door.option." + option), now.equals(option)));
            return new Column.Buttons(keys);
        }

        void group(String key, Column.Row... rows) {
            this.rows.add(Door.group(x.text("door.group." + key), rows));
        }

        /** The meters that have a value; a door with no run shows them blank. */
        void readings(Column.Row... rows) {
            this.rows.add(Door.group(x.text("door.group.readings"), rows));
        }

        String reading(String key, double scale, int digits) {
            var results = x.state().shown(unit.id());
            double value = results == null ? Double.NaN : results.reading(unit.id(), key);
            return Double.isNaN(value) ? "" : SheetUnits.number(digits, value / scale);
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : SheetUnits.number(2, value);
    }

    /** The door of {@code id}, whose type has a door here: every lit unit but the column, which {@link ColumnDoors} builds. */
    public static Door door(SheetContext x, String id, Door.View view) {
        var unit = x.editor().unit(id);
        var r = new Rows(x, unit);
        var u = x.units();
        Glass glass = null;
        switch (unit.type()) {
            case "valve" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "drop"));
                r.group("set_point", r.text("target", "outlet").equals("drop") ? r.entry("drop", x.text("door.drop"), Quantity.PRESSURE) : r.entry("pressure", x.text("door.outlet"), Quantity.PRESSURE));
                r.rows.add(phases(x, x.outOf(id, "out")));
                r.readings(Door.meter(x.text("door.drop"), r.reading("drop", u.pressure().si(1), 2), u.pressure().symbol()), Door.meter(x.text("door.cooled"), r.reading("cooled", 1, 1), "K"));
            }
            case "mixer" -> {
                int inlets = unit.params().integer("inlets", 2);
                r.group("inlets", new Column.Setting("inlets", x.text("door.inlets"), Integer.toString(inlets), "", null, true));
            }
            case "splitter" -> {
                r.group("split", r.choice("mode", "shares", "shares", "flows"), new Column.Buttons(List.of(Door.key("add_outlet", x.text("door.add_outlet"), true, null))));
                var rest = rest(x, unit);
                if (rest == null) r.readings(Door.meter(x.text("door.sum"), r.reading("sum", .01, 1), "%"));
                else r.readings(Door.meter(x.text("door.sum"), r.reading("sum", .01, 1), "%"), Door.meter(x.text("door.rest"), rest.name(), ""));
            }
            case "filter" -> r.readings(Door.meter(x.text("door.solids"), r.reading("solids", 1, 2), "kg/s"));
            case "drum" -> {
                r.group("pressure", r.choice("pressure_mode", "inlet", "inlet", "set"), r.text("pressure_mode", "inlet").equals("set") ? r.entry("pressure", x.text("door.pressure"), Quantity.PRESSURE) : r.entry("drop", x.text("door.drop"), Quantity.PRESSURE));
                heat(r);
                r.rows.add(phases(x, unit.type(), id));
                r.readings(Door.meter(x.text("door.temperature"), reading(x, unit, "temperature", u), u.temperature().symbol()), Door.meter(x.text("door.pressure"), reading(x, unit, "pressure", u), u.pressure().symbol()));
            }
            case "storage_tank" -> {
                r.group("pressure", r.entry("ambient", x.text("door.ambient"), Quantity.PRESSURE));
                heat(r);
                r.rows.add(phases(x, unit.type(), id));
                r.readings(Door.meter(x.text("door.temperature"), reading(x, unit, "temperature", u), u.temperature().symbol()), Door.meter(x.text("door.pressure"), reading(x, unit, "pressure", u), u.pressure().symbol()));
            }
            case "exchanger", "kettle" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "duty", "area"), r.choice("side", "shell", "tube", "shell"));
                String target = r.text("target", "outlet");
                r.group("set_point", switch (target) {
                    case "duty" -> r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT);
                    case "area" -> r.entry("area", x.text("door.area"), Quantity.PLAIN);
                    default -> r.entry("temperature", x.text("door.outlet"), Quantity.TEMPERATURE);
                });
                r.group("drops", r.entry("tube_drop", x.text("door.tube"), Quantity.PRESSURE), r.entry("shell_drop", x.text("door.shell"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"), Door.meter(x.text("door.approach"), r.reading("approach", 1, 1), "K"), Door.meter(x.text("door.area_needed"), r.reading("area_needed", 1, 0), "m²"));
                if (unit.type().equals("exchanger") && view.tab == 0) glass = heat(x, unit, HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE);
            }
            case "air_cooler" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "duty"));
                r.group("set_point", r.text("target", "outlet").equals("duty") ? r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("temperature", x.text("door.outlet"), Quantity.TEMPERATURE));
                r.group("drops", r.entry("drop", x.text("door.drop"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"));
            }
            case "fired_heater" -> {
                r.group("coils", r.choice("coils", "series", "series", "apart"));
                r.group("target", r.choice("target", "outlet", "outlet", "duty"));
                boolean apart = r.text("coils", "series").equals("apart");
                r.group("set_point", r.text("target", "outlet").equals("duty") ? r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("temperature", x.text("door.coil_outlet"), Quantity.TEMPERATURE), r.entry("limit", x.text("door.limit"), Quantity.MEGAWATT));
                if (apart) r.group("convection", r.choice("convection_target", "outlet", "outlet", "duty"), r.text("convection_target", "outlet").equals("duty") ? r.entry("convection_duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("convection_temperature", x.text("door.outlet"), Quantity.TEMPERATURE));
                r.group("drops", r.entry("radiant_drop", x.text("door.radiant"), Quantity.PRESSURE), r.entry("convection_drop", x.text("door.convection"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"), Door.meter(x.text("door.vapour_out"), r.reading("vapour", .01, 1), "%"));
                if (view.tab == 0) glass = heat(x, unit, HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT);
            }
            case "pump", "compressor" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "rise"));
                r.group("set_point", r.text("target", "outlet").equals("rise") ? r.entry("rise", x.text("door.rise"), Quantity.PRESSURE) : r.entry("pressure", x.text("door.outlet"), Quantity.PRESSURE));
                var meters = new ArrayList<Column.Row>(List.of(Door.meter(x.text("door.power"), r.reading("power", 1e3, 1), "kW"), Door.meter(x.text("door.rise"), r.reading("rise", u.pressure().si(1), 2), u.pressure().symbol())));
                if (unit.type().equals("pump")) meters.add(Door.meter(x.text("door.head"), r.reading("head", 1, 1), "m"));
                r.readings(meters.toArray(Column.Row[]::new));
            }
            default -> throw new IllegalArgumentException("No door for " + unit.type());
        }
        if (glass == null) glass = view.tab == 1 ? streams(x, unit) : sketch(x, unit);
        var title = x.t().get(Tiles.name(unit.type()));
        var status = status(x, unit);
        var entries = r.entries;
        return new Door(title, Door.tabs(view.tab, x.text("door.tab.design"), x.text("door.tab.streams")), view.tab, status.text(), status.tone(), false, List.of(r.rows), r.values, glass.door(), List.of(), new Door.Actions() {
            @Override public void entry(String key, String text) {
                write(x, id, key, text, entries);
            }

            @Override public void button(String key) {
                if (key.startsWith("tab/")) view.tab = Integer.parseInt(key.substring(4));
                else press(x, id, key);
            }
        });
    }

    private static void heat(Rows r) {
        var x = r.x;
        var rows = new ArrayList<Column.Row>();
        rows.add(r.choice("heat", "none", "none", "temperature", "duty"));
        String heat = r.text("heat", "none");
        if (heat.equals("temperature")) rows.add(r.entry("temperature", x.text("door.temperature"), Quantity.TEMPERATURE));
        if (heat.equals("duty")) rows.add(r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT));
        r.group("heat", rows.toArray(Column.Row[]::new));
    }

    private static String reading(SheetContext x, Flowsheet.Unit unit, String key, SheetUnits u) {
        var results = x.state().shown(unit.id());
        double value = results == null ? Double.NaN : results.reading(unit.id(), key);
        if (Double.isNaN(value)) return "";
        return key.equals("temperature") ? u.t(value) : u.p(value);
    }

    private static Column.Group phases(SheetContext x, Flowsheet.@Nullable Stream stream) {
        var props = x.props(stream);
        return Door.phases(x.text("door.group.phases"), props == null ? StreamProps.Phases.NONE : props.phases(), x.t());
    }

    /** A vessel's phases: of what it holds, its streams out together. */
    private static Column.Group phases(SheetContext x, String type, String id) {
        double gas = 0, oil = 0, water = 0;
        for (var stream : x.streams(id, false)) {
            var props = x.props(stream);
            if (props == null) continue;
            switch (stream.from().port()) {
                case "gas" -> gas += props.moles();
                case "oil" -> oil += props.moles();
                default -> water += props.moles();
            }
        }
        double all = gas + oil + water;
        var phases = all == 0 ? StreamProps.Phases.NONE : new StreamProps.Phases(100 * oil / all, 100 * water / all, 100 * gas / all, 0);
        return Door.phases(x.text("door.group.phases"), phases, x.t());
    }

    /** What a door's status says. */
    public static SheetState.Status status(SheetContext x, Flowsheet.Unit unit) {
        var results = x.state().shown(unit.id());
        if (results == null) return new SheetState.Status(x.text("door.status.not_solved"), Palette.MUTED);
        var calculation = unit.id();
        for (var note : NoteText.all(results.run()))
            if (note.severity() != com.wormzjl.icheme.science.flowsheet.Note.Severity.INFO && (note.subject().equals(calculation) || note.subject().startsWith(calculation + "/")))
                return new SheetState.Status(NoteText.text(note, x.t(), x.editor().sheet(), x.units()), note.severity() == com.wormzjl.icheme.science.flowsheet.Note.Severity.ERROR ? Palette.RED : Palette.WARN);
        double duty = results.reading(unit.id(), "duty");
        return new SheetState.Status(switch (unit.type()) {
            case "exchanger", "kettle" -> x.text("door.status.exchanger", SheetUnits.megawatts(duty), SheetUnits.number(1, results.reading(unit.id(), "approach")));
            case "fired_heater" -> x.text("door.status.heater", SheetUnits.megawatts(Math.abs(duty)), SheetUnits.number(1, unit.params().number("limit", 0) / 1e6));
            case "air_cooler" -> x.text("door.status.duty", SheetUnits.megawatts(Math.abs(duty)));
            case "pump", "compressor" -> {
                var p = x.props(x.into(unit.id(), "in"));
                yield p == null ? x.text("door.status.solved") : x.text("door.status.mover", Door.upper(StreamDoors.phaseName(x, p)), SheetUnits.number(1, p.vapour()));
            }
            case "drum" -> held(x, unit);
            case "splitter" -> {
                var rest = rest(x, unit);
                yield rest == null ? x.text("door.status.solved") : x.text("door.status.rest", rest.name());
            }
            case "valve" -> x.text("door.status.valve", SheetUnits.number(1, 100 * results.reading(unit.id(), "vapour")));
            default -> x.text("door.status.solved");
        }, Palette.ACCENT);
    }

    /** The phases a vessel holds, from what came in. */
    private static String held(SheetContext x, Flowsheet.Unit unit) {
        var p = x.props(x.into(unit.id(), "in"));
        if (p == null) return x.text("door.status.solved");
        var phases = p.phases();
        var names = new ArrayList<String>();
        if (phases.gas() > 0) names.add(Door.lower(x.text("phase.gas")));
        if (phases.organic() > 0) names.add(Door.lower(x.text("phase.organic")));
        if (phases.aqueous() > 0) names.add(Door.lower(x.text("phase.aqueous")));
        if (phases.solid() > 0) names.add(Door.lower(x.text("phase.solid")));
        return x.text("door.status.phases", String.join(", ", names));
    }

    /** The splitter's outlet with no share or flow of its own, which takes what the others leave. */
    private static Flowsheet.@Nullable Stream rest(SheetContext x, Flowsheet.Unit unit) {
        for (var row : unit.params().rows("outlets"))
            if (Double.isNaN(row.number("share", Double.NaN)) && Double.isNaN(row.number("flow", Double.NaN))) return x.outOf(unit.id(), "out/" + row.text("id", ""));
        return null;
    }

    private static void write(SheetContext x, String id, String key, String text, Map<String, Quantity> entries) {
        String step = "entry " + id + " " + key;
        if (key.equals("name")) {
            x.editor().rename(id, text);
            return;
        }
        var value = SheetContext.number(text);
        if (key.startsWith("share/") || key.startsWith("flow/")) {
            row(x, id, key.split("/")[1], key.split("/")[0], text.isBlank() ? null : value == null ? null : key.startsWith("share/") ? value / 100 : value / 3.6, step);
            return;
        }
        var quantity = entries.get(key);
        if (quantity == null) return;
        if (key.equals("limit") && text.isBlank()) {
            var json = x.editor().unit(id).params().json().deepCopy();
            json.remove("limit");
            x.editor().setParams(id, new Params(json), step);
            return;
        }
        if (value == null) return;
        var u = x.units();
        x.editor().set(id, key, switch (quantity) {
            case PRESSURE -> u.pressure().si(value);
            case TEMPERATURE -> u.temperature().si(value);
            case MEGAWATT -> value * 1e6;
            case PLAIN -> value;
        }, step);
    }

    /** Sets one outlet row's value; blank makes it the row that takes the rest. */
    private static void row(SheetContext x, String id, String row, String field, @Nullable Double value, String step) {
        var json = x.editor().unit(id).params().json().deepCopy();
        for (var element : json.getAsJsonArray("outlets")) {
            var object = element.getAsJsonObject();
            if (!object.get("id").getAsString().equals(row)) continue;
            object.remove("share");
            object.remove("flow");
            if (value != null) object.addProperty(field, value);
        }
        x.editor().setParams(id, new Params(json), step);
    }

    private static void press(SheetContext x, String id, String key) {
        var unit = x.editor().unit(id);
        if (key.startsWith("set/")) {
            var parts = key.split("/");
            x.editor().set(id, parts[1], parts[2], null);
        } else if (key.equals("inlets-") || key.equals("inlets+")) {
            int inlets = Math.max(2, unit.params().integer("inlets", 2) + (key.endsWith("+") ? 1 : -1));
            x.editor().set(id, "inlets", (double) inlets, null);
        } else if (key.equals("add_outlet")) {
            var json = unit.params().json().deepCopy();
            var rows = json.has("outlets") ? json.getAsJsonArray("outlets") : new JsonArray();
            var row = new JsonObject();
            row.addProperty("id", x.editor().rowId(id, "outlets"));
            row.addProperty(unit.params().text("mode", "shares").equals("flows") ? "flow" : "share", 0);
            rows.add(row);
            json.add("outlets", rows);
            x.editor().setParams(id, new Params(json), null);
        } else if (key.startsWith("remove/")) {
            var json = unit.params().json().deepCopy();
            var rows = json.getAsJsonArray("outlets");
            for (int i = rows.size() - 1; i >= 0; i--) if (rows.get(i).getAsJsonObject().get("id").getAsString().equals(key.substring(7))) rows.remove(i);
            x.editor().setParams(id, new Params(json), null);
        }
    }

    // ---- The glass ----

    /** A glass, with the slots it keeps. */
    private interface Glass {
        void draw(Canvas c, Skin skin, Rect box);

        default List<Door.Slot> slots(Rect box) {
            return List.of();
        }

        default Door.Glass door() {
            var me = this;
            return new Door.Glass() {
                @Override public void draw(Canvas c, Skin skin, Rect box) {
                    me.draw(c, skin, box);
                }

                @Override public List<Door.Slot> slots(Rect box) {
                    return me.slots(box);
                }
            };
        }
    }

    /** The shipped drawing of an exchanger's or a heater's assembly, read from the unit's streams. */
    private static Glass heat(SheetContext x, Flowsheet.Unit unit, HeatSides.Unit kind, HeatSides.Side side) {
        return (c, skin, box) -> {
            var drawing = new HeatFlowsheet(HeatDiagram.of(kind, side, ExchangerBlueprint.Design.NONE), HeatDiagram.roles(kind, side));
            drawing.fit(box.x(), box.y(), box.w(), box.h());
            drawing.draw(c, data(x, unit));
        };
    }

    private static Flowsheet.@Nullable Stream role(SheetContext x, Flowsheet.Unit unit, String role) {
        String id = unit.id();
        boolean apart = unit.type().equals("fired_heater") && x.editor().unit(id).params().text("coils", "series").equals("apart");
        return switch (role) {
            case "TUBE_INLET" -> x.into(id, "tube_in");
            case "TUBE_OUTLET" -> x.outOf(id, "tube_out");
            case "SHELL_INLET" -> x.into(id, "shell_in");
            case "SHELL_OUTLET" -> x.outOf(id, "shell_out");
            case "CONVECTION_INLET" -> apart ? x.into(id, "convection_in") : x.into(id, "in");
            case "CONVECTION_OUTLET" -> apart ? x.outOf(id, "convection_out") : null;
            case "RADIANT_INLET" -> apart ? x.into(id, "radiant_in") : null;
            case "RADIANT_OUTLET" -> apart ? x.outOf(id, "radiant_out") : x.outOf(id, "out");
            default -> null;
        };
    }

    private static HeatFlowsheet.Data data(SheetContext x, Flowsheet.Unit unit) {
        var u = x.units();
        var results = x.state().shown(unit.id());
        return new HeatFlowsheet.Data() {
            @Override public String value(HeatDiagram.Value value) {
                for (var source : value.sources()) {
                    if (value.show() == HeatDiagram.Show.DUTY) return results == null ? "" : SheetUnits.megawatts(Math.abs(results.reading(unit.id(), "duty"))) + " MW";
                    if (!source.startsWith("port:")) continue;
                    var props = x.props(role(x, unit, source.substring(5).split(":")[0]));
                    if (props == null) continue;
                    if (value.show() == HeatDiagram.Show.TEMPERATURE) return u.t(props.temperature()) + " " + u.temperature().symbol();
                    return SheetUnits.number(1, props.mass()) + " kg/s";
                }
                return "";
            }

            @Override public String state(String slot) {
                return results == null ? "NOT_VALIDATED" : "OK";
            }

            @Override public String text(AssemblyDiagram.Text text) {
                return x.t().get(HeatDiagram.KEY.substring("gui.icheme.".length()) + text.key(), text.args().toArray());
            }
        };
    }

    /** The streams side by side: their properties, one column a stream, as many as the glass holds. */
    private static Glass streams(SheetContext x, Flowsheet.Unit unit) {
        return (c, skin, box) -> table(c, x, unit, box);
    }

    /** A column of the Streams table. */
    private record Cell(String head, @Nullable StreamProps props) {
    }

    private static void table(Canvas c, SheetContext x, Flowsheet.Unit unit, Rect box) {
        var cells = new ArrayList<Cell>();
        for (var stream : x.streams(unit.id(), true)) cells.add(new Cell(StreamDoors.portName(x, stream.to().port()) + " " + stream.name(), x.props(stream)));
        for (var stream : x.streams(unit.id(), false)) cells.add(new Cell(StreamDoors.portName(x, stream.from().port()) + " " + stream.name(), x.props(stream)));
        x.openOutlets(unit).forEach((port, props) -> cells.add(new Cell(x.text("door.open_outlet", StreamDoors.portName(x, port)), props)));
        int cell = 90, room = Math.max(1, (box.w() - 130) / cell), n = Math.min(room, cells.size());
        int[] widths = new int[n + 1];
        widths[0] = box.w() - n * cell - 6;
        for (int i = 1; i <= n; i++) widths[i] = cell;
        var u = x.units();
        var rows = new ArrayList<String[]>();
        var head = new String[n + 1];
        head[0] = "";
        var labels = List.of(x.text("label.temperature", u.temperature().symbol()), x.text("label.pressure", u.pressure().symbol()), x.text("label.flow", u.molesUnit()), x.text("label.flow", u.tonnesUnit()), x.text("label.vapour"), x.text("label.density"));
        for (int i = 0; i < n; i++) head[i + 1] = cells.get(i).head();
        rows.add(head);
        for (int r = 0; r < labels.size(); r++) {
            var row = new String[n + 1];
            row[0] = labels.get(r);
            for (int i = 0; i < n; i++) {
                var p = cells.get(i).props();
                row[i + 1] = p == null ? "" : switch (r) {
                    case 0 -> u.t(p.temperature());
                    case 1 -> u.p(p.pressure());
                    case 2 -> u.moles(p.moles());
                    case 3 -> u.tonnes(p.mass());
                    case 4 -> SheetUnits.number(1, p.vapour());
                    default -> SheetUnits.number(1, p.density());
                };
            }
            rows.add(row);
        }
        Door.table(c, box, widths, rows);
    }

    private static final int SKETCH = 118, MID = 58, PILL = 56;

    private static void pill(FlowsheetPainter p, String name, int x, int y, boolean free) {
        p.pill(x, y, PILL, SheetLayout.PILL_HALF);
        p.fitted(name, x + PILL / 2, y, PILL, free ? Palette.MUTED : Palette.TEXT);
    }

    /** The unit on the glass above its table: its symbol, its stream in, its streams out as pills. */
    private static Glass sketch(SheetContext x, Flowsheet.Unit unit) {
        return new Glass() {
            @Override public void draw(Canvas c, Skin skin, Rect box) {
                var symbol = Tiles.symbol(unit.type());
                var p = new FlowsheetPainter(c, box.x(), box.y(), 1, 1);
                int gx = box.w() / 2 - symbol.w / 2, gy = symbol == Symbol.PUMP ? MID - 13 : MID - symbol.h / 2, inX = gx - 96, outX = gx + symbol.w + 40;
                var in = x.streams(unit.id(), true);
                var out = outlets(x, unit);
                p.wire(new int[]{inX + PILL, MID, gx, MID}, true, false, Palette.MUTED);
                SheetPainter.sprite(c, symbol, box.x() + gx, box.y() + gy, symbol.w, symbol.h, SheetPainter.look(x.state().tone(unit.id())));
                pill(p, in.isEmpty() ? x.text("door.none") : in.getFirst().name(), inX, MID, in.isEmpty());
                for (int i = 0; i < out.size(); i++) {
                    int y = out.size() == 1 ? MID : MID - 36 + i * 72 / (out.size() - 1), cx = gx + symbol.w / 2;
                    int[] points = out.size() == 1 || y == MID ? new int[]{gx + symbol.w, MID, outX, MID} : y < MID ? new int[]{cx, gy, cx, y, outX, y} : new int[]{cx, gy + symbol.h, cx, y, outX, y};
                    p.wire(points, true, false, Palette.MUTED);
                    p.square(points[0], points[1], SheetLayout.PORT, PortColours.CONNECTED);
                    pill(p, out.get(i), outX, y, out.get(i).equals(x.text("door.none")));
                }
                p.square(gx, MID, SheetLayout.PORT, PortColours.CONNECTED);
                p.tag(unit.name(), out.size() > 1 ? gx - 8 : gx + symbol.w / 2, out.size() > 1 ? gy + symbol.h + 12 : gy - 8, .5f, Palette.TEXT, 0xFF10181D);
                c.hLine(box.x() + 2, box.right() - 2, box.y() + SKETCH, Palette.LINE);
                var rest = new Rect(box.x(), box.y() + SKETCH + 3, box.w(), box.h() - SKETCH - 3);
                if (unit.type().equals("splitter")) split(c, skin, x, unit, rest);
                else table(c, x, unit, rest);
            }

            @Override public List<Door.Slot> slots(Rect box) {
                if (!unit.type().equals("splitter")) return List.of();
                var rest = new Rect(box.x(), box.y() + SKETCH + 3, box.w(), box.h() - SKETCH - 3);
                var slots = new ArrayList<Door.Slot>();
                boolean flows = mode(unit).equals("flows");
                int i = 0, y = rest.y() + 18;
                for (var row : unit.params().rows("outlets")) {
                    String id = row.text("id", ""), field = flows ? "flow" : "share";
                    double value = row.number(field, Double.NaN);
                    String text = Double.isNaN(value) ? "" : flows ? x.units().moles(value) : SheetUnits.number(1, value * 100);
                    slots.add(Door.Slot.entry(field + "/" + id, new Rect(rest.x() + 110, y + i * 22 - 1, Column.BOX, 18), text, x.text("door.rest")));
                    slots.add(Door.Slot.key("remove/" + id, new Rect(rest.right() - 22, y + i * 22 - 1, 18, 18), "x", false));
                    i++;
                }
                return slots;
            }
        };
    }

    private static String mode(Flowsheet.Unit unit) {
        return unit.params().text("mode", "shares");
    }

    /** The pills of a unit's outlets, by its ports: a free port's reads none. */
    private static List<String> outlets(SheetContext x, Flowsheet.Unit unit) {
        var names = new ArrayList<String>();
        var box = x.editor().layout().box(unit.id());
        for (var place : box.places()) {
            if (place.direction() != com.wormzjl.icheme.science.flowsheet.Port.Direction.OUT) continue;
            var stream = x.outOf(unit.id(), place.port());
            names.add(stream == null ? x.text("door.none") : stream.name());
        }
        return names;
    }

    private static void split(Canvas c, Skin skin, SheetContext x, Flowsheet.Unit unit, Rect rest) {
        var m = c.measure();
        boolean flows = mode(unit).equals("flows");
        int y = rest.y() + 4;
        String amount = x.units().molesUnit();
        c.text(x.text("door.outlet"), rest.x() + 4, y, Palette.MUTED);
        c.text(flows ? amount : x.text("door.share"), rest.x() + 110, y, Palette.MUTED);
        c.text(amount, rest.right() - 26 - m.width(amount), y, Palette.MUTED);
        c.hLine(rest.x() + 2, rest.right() - 2, y + 10, Palette.LINE);
        int i = 0;
        for (var row : unit.params().rows("outlets")) {
            int ry = y + 14 + i * 22;
            if (i % 2 == 1) c.fill(rest.x(), ry - 2, rest.right(), ry + 20, DialogColumns.BAND);
            var stream = x.outOf(unit.id(), "out/" + row.text("id", ""));
            c.text(stream == null ? x.text("door.outlet_row", row.text("id", "")) : stream.name(), rest.x() + 4, ry + 5, Palette.TEXT);
            var props = x.props(stream);
            if (props != null) {
                String flow = x.units().moles(props.moles());
                c.text(flow, rest.right() - 26 - m.width(flow), ry + 5, Palette.ACCENT);
            }
            i++;
        }
    }
}

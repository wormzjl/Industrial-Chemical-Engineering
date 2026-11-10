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
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
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
        PRESSURE, TEMPERATURE, MEGAWATT, PLAIN,
        /** A volume stored in m³, shown in litres; a time stored in s, shown in hours; a share stored 0 to 1, shown in percent. */
        LITRES, HOURS, PERCENT
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
                case LITRES -> trim(1000 * value);
                case HOURS -> trim(value / 3600);
                case PERCENT -> trim(100 * value);
            });
            return Door.entry(field, label, symbol(quantity, field, u));
        }

        /** An entry on a still's cut row, {@code cut/<id>/value}: the head's temperature or the batch's time, in the player's unit. */
        Column.Field cut(Params row, String label) {
            String key = "cut/" + row.text("id", "") + "/value";
            var quantity = row.text("end", "head").equals("time") ? Quantity.HOURS : Quantity.TEMPERATURE;
            double value = row.number("value", Double.NaN);
            entries.put(key, quantity);
            values.put(key, Double.isNaN(value) ? "" : quantity == Quantity.HOURS ? trim(value / 3600) : x.units().t(value));
            return Door.entry(key, label, symbol(quantity, key, x.units()));
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
            return Reader.of(x, unit.id()).of(key, scale, digits);
        }
    }

    private static String symbol(Quantity quantity, String field, SheetUnits u) {
        return switch (quantity) {
            case PRESSURE -> u.pressure().symbol();
            case TEMPERATURE -> u.temperature().symbol();
            case MEGAWATT -> "MW";
            case PLAIN -> field.equals("area") ? "m²" : field.equals("tip") ? "m" : "";
            case LITRES -> "L";
            case HOURS -> "h";
            case PERCENT -> "%";
        };
    }

    /** A still's rows (batch 2026-11-09-batch-distiller, W7): the pot and cycle, the heater, the condenser and column, the cuts, the batch. */
    private static void still(Rows r, SheetContext x, String id) {
        var u = x.units();
        var p = r.unit.params();
        r.group("pot", r.entry("volume", x.text("door.pot"), Quantity.LITRES), r.entry("cycle", x.text("door.cycle"), Quantity.HOURS));
        r.group("heater", r.choice("heater", "solid", "solid", "liquid", "electric"), r.entry("firing", x.text("door.firing"), Quantity.PERCENT));
        boolean condenses = com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.condenses(p);
        if (condenses) {
            int stages = p.integer("stages", 0);
            var column = new ArrayList<Column.Row>(List.of(r.choice("condenser", "built_in", "built_in", "none"), r.entry("water", x.text("door.water"), Quantity.TEMPERATURE),
                    new Column.Setting("stages", x.text("door.stages"), Integer.toString(stages), "", null, true)));
            if (stages > 0) column.add(r.entry("reflux", x.text("door.reflux"), Quantity.PLAIN));
            r.group("condenser", column.toArray(Column.Row[]::new));
        } else r.group("condenser", r.choice("condenser", "built_in", "built_in", "none"));
        var rows = com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.cutRows(p);
        int most = com.wormzjl.icheme.science.flowsheet.unit.StillBatch.MOST_CUTS;
        r.group("cuts", new Column.Buttons(List.of(Door.key("add_cut", x.text("door.add_cut"), rows.size() < most, null), Door.key("remove_cut", x.text("door.remove_cut"), rows.size() > 1, null))));
        for (var row : rows) {
            String cut = row.text("id", ""), end = row.text("end", "head");
            var keys = new Column.Buttons(List.of(Door.position("cut_end/" + cut + "/head", x.text("door.option.head"), end.equals("head")), Door.position("cut_end/" + cut + "/time", x.text("door.option.time"), end.equals("time"))));
            r.rows.add(Door.group(x.text("door.cut", cut), keys, r.cut(row, x.text(end.equals("time") ? "door.cut_time" : "door.cut_head"))));
        }
        var meters = new ArrayList<Column.Row>(List.of(Door.meter(x.text("door.heat"), r.reading("duty", 1e3, 1), "kW")));
        if (r.text("heater", "solid").equals("electric"))
            meters.add(Door.meter(x.text("door.draw"), r.reading("fuel", 20 * x.host().joulesPerFE(), 0), "FE/t"));
        else meters.add(Door.meter(x.text("door.fuel"), r.reading("fuel", 1e3, 1), "kW"));
        if (condenses) meters.add(Door.meter(x.text("door.condenser"), r.reading("condenser", 1e3, 1), "kW"));
        meters.add(Door.meter(x.text("door.batch"), r.reading("batch", 3600, 2), "h"));
        meters.add(Door.meter(x.text("door.fill"), r.reading("fill", .01, 0), "%"));
        meters.add(Door.meter(x.text("door.distilled"), r.reading("distilled", .01, 0), "%"));
        meters.add(Door.meter("pot_end", x.text("door.pot_end"), Reader.of(x, id).temperature(u, "pot_end"), u.temperature().symbol()));
        r.readings(meters.toArray(Column.Row[]::new));
    }


    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : SheetUnits.number(2, value);
    }

    /** The door of {@code id}, whose type has a door here: every lit unit but the column, which {@link ColumnDoors} builds. */
    public static Door door(SheetContext x, String id, Door.View view) {
        var unit = x.editor().unit(id);
        var r = new Rows(x, unit);
        var u = x.units();
        Door.Glass glass = null;
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
            // A flare stack (batch 2026-11-09-flare-stack, W4): its tip, then what its flame does with the gas.
            case "flare" -> {
                r.group("tip", r.entry("tip", x.text("door.tip"), Quantity.PLAIN), Door.meter(x.text("door.rated"), r.reading("rated", 1e6, 1), "MW"));
                r.readings(Door.meter(x.text("door.heat_release"), r.reading("heat", 1e6, 2), "MW"), Door.meter(x.text("door.heating_value"), r.reading("heating_value", 1e6, 1), "MJ/m³"),
                        Door.meter(x.text("door.flame"), Reader.of(x, id).temperature(u, "flame"), u.temperature().symbol()), Door.meter(x.text("door.most_flow"), r.reading("limit", 1, 2), "kg/s"));
            }
            // A chimney (batch 2026-11-10-chimney): its form and sizes, then what its stack does with the gas.
            case "chimney" -> {
                r.group("chimney", r.choice("form", "steel", "steel", "brick"), r.entry("diameter", x.text("door.diameter"), Quantity.PLAIN), r.entry("height", x.text("door.height"), Quantity.PLAIN),
                        r.entry("nearby", x.text("door.nearby"), Quantity.PLAIN));
                r.readings(Door.meter(x.text("door.exit_velocity"), r.reading("exit_velocity", 1, 1), "m/s"), Door.meter(x.text("door.draft"), r.reading("draft", 1, 0), "Pa"),
                        Door.meter(x.text("door.losses"), r.reading("losses", 1, 0), "Pa"), Door.meter(x.text("door.solids"), r.reading("solids", 1. / 3600, 2), "kg/h"),
                        Door.meter(x.text("door.opacity"), r.reading("opacity", .01, 0), "%"), Door.meter(x.text("door.gep"), r.reading("gep", 1, 0), "m"));
            }
            // A batch still (batch 2026-11-09-batch-distiller, W7): its curve on a tab of its own.
            case "batch_still" -> {
                still(r, x, id);
                if (view.tab == 2) glass = StillCurve.glass(x, unit);
            }
            case "drum" -> {
                r.group("pressure", r.choice("pressure_mode", "inlet", "inlet", "set"), r.text("pressure_mode", "inlet").equals("set") ? r.entry("pressure", x.text("door.pressure"), Quantity.PRESSURE) : r.entry("drop", x.text("door.drop"), Quantity.PRESSURE));
                heat(r);
                r.rows.add(phases(x, unit.type(), id));
                r.readings(Door.meter(x.text("door.temperature"), Reader.of(x, id).temperature(u), u.temperature().symbol()), Door.meter(x.text("door.pressure"), Reader.of(x, id).pressure(u), u.pressure().symbol()));
            }
            case "storage_tank" -> {
                r.group("pressure", r.entry("ambient", x.text("door.ambient"), Quantity.PRESSURE));
                heat(r);
                r.rows.add(phases(x, unit.type(), id));
                r.readings(Door.meter(x.text("door.temperature"), Reader.of(x, id).temperature(u), u.temperature().symbol()), Door.meter(x.text("door.pressure"), Reader.of(x, id).pressure(u), u.pressure().symbol()));
            }
            case "exchanger", "kettle" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "duty", "area"), r.choice("side", "shell", "tube", "shell"));
                String target = r.text("target", "outlet");
                r.group("set_point", switch (target) {
                    case "duty" -> r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT);
                    case "area" -> r.entry("area", x.text("door.area"), Quantity.PLAIN);
                    default -> r.entry("temperature", x.text("door.outlet"), Quantity.TEMPERATURE);
                });
                boolean kettle = unit.type().equals("kettle");
                // Sized (plan section 3.11 item 3), a side's drop is its law's at the run's flow: a meter. A kettle's shell is its pool, with no law.
                if (sized(x, unit)) r.group("drops_flow", drop(r, x, "tube_drop", "door.tube"), kettle ? r.entry("shell_drop", x.text("door.shell"), Quantity.PRESSURE) : drop(r, x, "shell_drop", "door.shell"));
                else r.group("drops", r.entry("tube_drop", x.text("door.tube"), Quantity.PRESSURE), r.entry("shell_drop", x.text("door.shell"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"), Door.meter(x.text("door.approach"), r.reading("approach", 1, 1), "K"), Door.meter(x.text("door.area_needed"), r.reading("area_needed", 1, 0), "m²"));
                if (unit.type().equals("exchanger") && view.tab == 0) glass = UnitGlass.heat(x, unit, HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE);
            }
            case "cooler" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "duty"));
                r.group("set_point", r.text("target", "outlet").equals("duty") ? r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("temperature", x.text("door.outlet"), Quantity.TEMPERATURE));
                if (sized(x, unit)) r.group("drops_flow", drop(r, x, "drop", "door.drop"));
                else r.group("drops", r.entry("drop", x.text("door.drop"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"));
            }
            case "fired_heater" -> {
                r.group("coils", r.choice("coils", "series", "series", "apart"));
                r.group("target", r.choice("target", "outlet", "outlet", "duty"));
                boolean apart = r.text("coils", "series").equals("apart");
                r.group("set_point", r.text("target", "outlet").equals("duty") ? r.entry("duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("temperature", x.text("door.coil_outlet"), Quantity.TEMPERATURE), r.entry("limit", x.text("door.limit"), Quantity.MEGAWATT));
                if (apart) r.group("convection", r.choice("convection_target", "outlet", "outlet", "duty"), r.text("convection_target", "outlet").equals("duty") ? r.entry("convection_duty", x.text("door.duty"), Quantity.MEGAWATT) : r.entry("convection_temperature", x.text("door.outlet"), Quantity.TEMPERATURE));
                if (!sized(x, unit)) r.group("drops", r.entry("radiant_drop", x.text("door.radiant"), Quantity.PRESSURE), r.entry("convection_drop", x.text("door.convection"), Quantity.PRESSURE));
                else if (apart) r.group("drops_flow", drop(r, x, "radiant_drop", "door.radiant"), drop(r, x, "convection_drop", "door.convection"));
                else r.group("drops_flow", drop(r, x, "coils", "door.coils"));
                r.readings(Door.meter(x.text("door.duty"), r.reading("duty", 1e6, 2), "MW"), Door.meter(x.text("door.vapour_out"), r.reading("vapour", .01, 1), "%"));
                if (view.tab == 0) glass = UnitGlass.heat(x, unit, HeatSides.Unit.FIRED_HEATER, HeatSides.Side.RADIANT);
            }
            case "pump", "compressor" -> {
                r.group("target", r.choice("target", "outlet", "outlet", "rise"));
                r.group("set_point", r.text("target", "outlet").equals("rise") ? r.entry("rise", x.text("door.rise"), Quantity.PRESSURE) : r.entry("pressure", x.text("door.outlet"), Quantity.PRESSURE));
                var meters = new ArrayList<Column.Row>(List.of(Door.meter(x.text("door.power"), r.reading("power", 1e3, 1), "kW"), Door.meter(x.text("door.rise"), r.reading("rise", u.pressure().si(1), 2), u.pressure().symbol())));
                if (unit.type().equals("pump")) meters.add(Door.meter(x.text("door.head"), r.reading("head", 1, 1), "m"));
                meters.add(Door.meter(x.text("door.efficiency"), r.reading("efficiency", .01, 1), "%"));
                r.readings(meters.toArray(Column.Row[]::new));
                // The proposed driver (W8 form A, D42): a motor with 20 % spare, its draw in kW and in FE/t.
                r.group("motor", Door.meter(x.text("door.rating"), r.reading("motor", 1e3, 1), "kW"), Door.meter("motor_efficiency", x.text("door.efficiency"), r.reading("motor_efficiency", .01, 1), "%"),
                        Door.meter(x.text("door.electric"), r.reading("electric", 1e3, 1), "kW"), Door.meter(x.text("door.draw"), r.reading("electric", 20 * x.host().joulesPerFE(), 0), "FE/t"));
            }
            case "steam_turbine" -> {
                r.group("pressure", r.entry("exhaust", x.text("door.exhaust"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.power"), r.reading("power", 1e3, 1), "kW"), Door.meter(x.text("door.efficiency"), r.reading("efficiency", .01, 1), "%"),
                        Door.meter(x.text("door.steam_rate"), r.reading("steam_rate", 1, 2), "kg/kWh"), Door.meter(x.text("door.isentropic"), r.reading("drop", 1e3, 1), "kJ/kg"));
            }
            case "ejector" -> {
                r.group("pressure", r.entry("discharge", x.text("door.discharge"), Quantity.PRESSURE), r.entry("motive", x.text("door.motive"), Quantity.PRESSURE));
                r.readings(Door.meter(x.text("door.steam_need"), r.reading("need", 1 / 3600., 0), "kg/h"), Door.meter(x.text("door.steam_in"), r.reading("motive", 1 / 3600., 0), "kg/h"),
                        Door.meter(x.text("door.compression"), r.reading("compression", 1, 1), ""));
            }
            default -> throw new IllegalArgumentException("No door for " + unit.type());
        }
        if (glass == null) glass = view.tab == 1 ? UnitGlass.streams(x, unit) : UnitGlass.sketch(x, unit);
        var title = x.t().get(Tiles.name(unit.type()));
        var status = status(x, unit);
        var entries = r.entries;
        var tabs = unit.type().equals("batch_still") ? Door.tabs(view.tab, x.text("door.tab.design"), x.text("door.tab.streams"), x.text("door.tab.batch"))
                : Door.tabs(view.tab, x.text("door.tab.design"), x.text("door.tab.streams"));
        return new Door(title, tabs, view.tab, status.text(), status.tone(), false, List.of(r.rows), r.values, glass, List.of(), new Door.Actions() {
            @Override public void entry(String key, String text) {
                write(x, id, key, text, entries);
            }

            @Override public void button(String key) {
                if (!view.press(key)) press(x, id, key);
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

    /** Whether the unit holds a size the constructor builds, whose law the run reads for its drops ({@code SizedPassages.of}). */
    static boolean sized(SheetContext x, Flowsheet.Unit unit) {
        var size = x.editor().sheet().sizing().unit(unit.id()).size();
        boolean law = switch (size) {
            case ExchangerSizing.Size exchanger -> exchanger.kettle() == unit.type().equals("kettle");
            case AirCoolerSizing.Size cooler -> true;
            case HeaterSizing.Size heater -> true;
            case null, default -> false;
        };
        return law && UnitSize.built(size) != null;
    }

    /** A drop the run read off a sized side, in the player's pressure unit. */
    private static Column.Reading drop(Rows r, SheetContext x, String key, String label) {
        var u = x.units();
        return Door.meter(key, x.text(label), r.reading(key, u.pressure().si(1), 2), u.pressure().symbol());
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
            case "cooler" -> x.text("door.status.duty", SheetUnits.megawatts(Math.abs(duty)));
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
            case "ejector" -> x.text("door.status.ejector", SheetUnits.number(0, 3600 * results.reading(unit.id(), "motive")), SheetUnits.number(1, results.reading(unit.id(), "compression")));
            case "batch_still" -> x.text("door.status.still", SheetUnits.number(2, results.reading(unit.id(), "batch") / 3600), SheetUnits.number(2, results.reading(unit.id(), "cycle") / 3600),
                    SheetUnits.number(0, 100 * results.reading(unit.id(), "distilled")));
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
            row(x, id, key.split("/")[1], key.split("/")[0], text.isBlank() ? null : value == null ? null : key.startsWith("share/") ? value / 100 : x.units().molesPerSecond(value), step);
            return;
        }
        var quantity = entries.get(key);
        if (quantity == null) return;
        if (key.startsWith("cut/")) {
            if (value == null) return;
            double si = quantity == Quantity.HOURS ? value * 3600 : x.units().temperature().si(value);
            var json = cutsJson(x.editor().unit(id));
            for (var element : json.getAsJsonArray("cuts"))
                if (element.getAsJsonObject().get("id").getAsString().equals(key.split("/")[1])) element.getAsJsonObject().addProperty("value", si);
            x.editor().setParams(id, new Params(json), step);
            return;
        }
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
            case LITRES -> value / 1000;
            case HOURS -> value * 3600;
            case PERCENT -> value / 100;
        }, step);
    }

    /** A still's settings with its cut rows written out, a dropped still's two included. */
    private static JsonObject cutsJson(Flowsheet.Unit unit) {
        var json = unit.params().json().deepCopy();
        if (!json.has("cuts") || !json.get("cuts").isJsonArray() || json.getAsJsonArray("cuts").isEmpty()) {
            var rows = new JsonArray();
            for (var row : com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.cutRows(Params.none())) rows.add(row.json());
            json.add("cuts", rows);
        }
        return json;
    }

    /** A still's keys: its stages stepped through none and 2 to 8, a cut added or the last removed, a cut's end, no condenser and so no column. */
    private static boolean stillKey(SheetContext x, String id, String key) {
        var unit = x.editor().unit(id);
        if (!unit.type().equals("batch_still")) return false;
        var json = cutsJson(unit);
        var rows = json.getAsJsonArray("cuts");
        switch (key) {
            case "stages-", "stages+" -> {
                int stages = unit.params().integer("stages", 0), most = com.wormzjl.icheme.science.fluid.network.StillColumn.MAX_STAGES;
                int least = com.wormzjl.icheme.science.fluid.network.BatchStill.MIN_STAGES;
                int next = key.endsWith("+") ? (stages == 0 ? least : Math.min(most, stages + 1)) : (stages <= least ? 0 : stages - 1);
                json.addProperty("stages", next);
            }
            case "set/condenser/none" -> {
                json.addProperty("condenser", "none");
                json.addProperty("stages", 0);
            }
            case "add_cut" -> {
                if (rows.size() >= com.wormzjl.icheme.science.flowsheet.unit.StillBatch.MOST_CUTS) return true;
                var last = rows.get(rows.size() - 1).getAsJsonObject();
                var row = new JsonObject();
                row.addProperty("id", x.editor().rowId(id, "cuts"));
                boolean time = last.has("end") && last.get("end").getAsString().equals("time");
                row.addProperty("end", time ? "time" : "head");
                double was = last.has("value") ? last.get("value").getAsDouble() : com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.SECOND_CUT;
                row.addProperty("value", time ? was + 1800 : was + 30);
                rows.add(row);
            }
            case "remove_cut" -> {
                if (rows.size() <= 1) return true;
                rows.remove(rows.size() - 1);
            }
            default -> {
                if (!key.startsWith("cut_end/")) return false;
                var parts = key.split("/");
                for (var element : rows) {
                    var row = element.getAsJsonObject();
                    if (!row.get("id").getAsString().equals(parts[1]) || row.get("end").getAsString().equals(parts[2])) continue;
                    // A cut changed to the other end starts at that end's own default: an hour, or the second cut's head.
                    row.addProperty("end", parts[2]);
                    row.addProperty("value", parts[2].equals("time") ? 3600 : com.wormzjl.icheme.science.flowsheet.unit.BatchStillUnit.SECOND_CUT);
                }
            }
        }
        x.editor().setParams(id, new Params(json), null);
        return true;
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
        if (stillKey(x, id, key)) return;
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

}

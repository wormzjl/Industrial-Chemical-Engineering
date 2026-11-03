package com.wormzjl.icheme.science.flowsheet.unit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.column.v3.V3SteamFeedSpec;
import com.wormzjl.icheme.science.column.v3.V3SideDrawSpec;
import com.wormzjl.icheme.science.column.v3.V3ComponentBasis;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.column.v3.V3PumparoundSpec;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * A column's door as a sheet holds it (batch 2026-11-02-process-simulator, plan sections 3.3 and 3.11): the calculator's Column
 * page, less the feed, which comes down the Feed line, with each row under its own id. A port names a row by that id, so removing
 * one row moves no other row's wires. Pressures, temperatures and flows are SI, and a duty keeps the column's sign: positive adds
 * heat to it, so a pumparound's is negative.
 *
 * @param condenser      what condenses the overhead; an exchanger has a cooling pair, an air cooler none
 * @param condenserDrop  the drop across the condenser's cold side (Pa)
 * @param reboilerDrop   the drop across the kettle's tube side (Pa)
 * @param units          how many identical units stand in parallel on the condenser, the kettle and each pumparound's cooler
 *                       (S3b): {@code condenser_units}, {@code kettle_units} and a pumparound row's {@code units}, 1 where absent
 * @param ejector        the ejector on the vapour line where the top is under ambient pressure (W10): {@code ejector_discharge} and
 *                       {@code ejector_motive}, the tile's defaults where absent
 */
public record ColumnDoor(String packageId, String assayId, int stages, int feedStage, double topPressure, double stageDrop, double diameter,
                         double condenserTemperature, double reflux, double reboilerDuty, ColumnComponents.Condenser condenser, double condenserDrop, double reboilerDrop,
                         List<Draw> draws, List<Steam> steam, List<Pumparound> pumparounds, ColumnComponents.Units units, Ejector ejector) {
    /** A side draw on {@code tray} (mol/s). */
    public record Draw(String id, int tray, double flow) {
    }

    /** A steam feed into {@code stage} (mol/s, K), which a wired Steam port replaces. */
    public record Steam(String id, int stage, double flow, double temperature) {
    }

    /** The ejector's discharge pressure and its motive steam's (Pa), as the ejector tile's door sets them. */
    public record Ejector(double discharge, double motive) {
    }

    /** The settings of the ejector's two pressures. */
    public static final String EJECTOR_DISCHARGE = "ejector_discharge", EJECTOR_MOTIVE = "ejector_motive";

    /** A pumparound; {@code drop} is its cooler's cold side (Pa). */
    public record Pumparound(String id, int returnTray, int drawTray, double duty, V3PumparoundSpec.Split split, double drop) {
    }

    /** This column on a feed: {@code flows} on {@code pack}'s components, mol/s. A column's steam stands as its rows set it. */
    public V3ColumnInput input(MaterialCatalog.Package pack, double[] flows, double temperature) {
        return input(pack, flows, temperature, steam.stream().map(s -> new V3SteamFeedSpec(s.stage(), s.flow(), s.temperature())).toList());
    }

    /** This column on a feed, with the steam a wired port brings joined to {@code steam}. */
    public V3ColumnInput input(MaterialCatalog.Package pack, double[] flows, double temperature, List<V3SteamFeedSpec> steam) {
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, pack.id(), assayId.isEmpty() ? pack.id() : assayId, new V3ComponentBasis(pack.components()), flows, temperature, stages, feedStage, topPressure, stageDrop,
                specifications(), draws.stream().map(d -> new V3SideDrawSpec(d.tray(), d.flow())).toList(), steam, pumparounds.stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.duty(), p.split())).toList(), diameter);
    }

    public static ColumnDoor of(Params p) {
        var ids = new HashSet<String>();
        var draws = new ArrayList<Draw>();
        for (var row : p.rows("draws")) draws.add(new Draw(id(row, "draw", ids), (int) row.number("tray"), row.number("flow")));
        var steam = new ArrayList<Steam>();
        for (var row : p.rows("steam")) steam.add(new Steam(id(row, "steam", ids), (int) row.number("stage"), row.number("flow"), row.number("temperature")));
        var pumparounds = new ArrayList<Pumparound>();
        var coolers = new ArrayList<Integer>();
        for (var row : p.rows("pumparounds")) {
            pumparounds.add(new Pumparound(id(row, "pa", ids), (int) row.number("return_tray"), (int) row.number("draw_tray"), row.number("duty"), split(row), row.number("drop", 0)));
            coolers.add(units(row, UNITS));
        }
        return new ColumnDoor(p.text("package", ""), p.text("assay", ""), (int) p.number("stages"), (int) p.number("feed_stage"), p.number("top_pressure"), p.number("stage_drop"), p.number("diameter", 0),
                p.number("condenser_temperature"), p.number("reflux"), p.number("reboiler_duty"), condenser(p), p.number("condenser_drop", 0), p.number("reboiler_drop", 0), draws, steam, pumparounds,
                new ColumnComponents.Units(units(p, CONDENSER_UNITS), units(p, KETTLE_UNITS), coolers),
                new Ejector(p.number(EJECTOR_DISCHARGE, EjectorUnit.DISCHARGE_PRESSURE), p.number(EJECTOR_MOTIVE, EjectorUnit.MOTIVE_PRESSURE)));
    }

    /** The settings that hold the member counts: the door's two and a pumparound row's own. */
    public static final String CONDENSER_UNITS = "condenser_units", KETTLE_UNITS = "kettle_units", UNITS = "units";

    /** A member count: 1 where absent, a whole number from 1 to {@link ColumnComponents#MAX_UNITS}, else refused by name. */
    private static int units(Params p, String key) {
        double n = p.number(key, 1);
        if (n != Math.rint(n) || n < 1 || n > ColumnComponents.MAX_UNITS) throw new FlowsheetFailure("unit.setting", key);
        return (int) n;
    }

    /** The components a Save design names: the condenser, the counts, and the pumps the door's rows hold, standard where none. */
    public ColumnComponents.Choices choices(ColumnComponents.Pump reflux, List<ColumnComponents.Pump> pumps) {
        return new ColumnComponents.Choices(condenser, reflux, pumps, units).fit(pumparounds.size());
    }

    private static String id(Params row, String kind, HashSet<String> ids) {
        String id = row.text("id", "");
        if (id.isEmpty() || !ids.add(kind + "/" + id)) throw new FlowsheetFailure("column.row_id", kind, id);
        return id;
    }

    private static V3PumparoundSpec.Split split(Params row) {
        try {
            return V3PumparoundSpec.Split.valueOf(row.text("split", V3PumparoundSpec.Split.RETURN_TRAY.name()));
        } catch (IllegalArgumentException unknown) {
            throw new FlowsheetFailure("unit.setting", "split");
        }
    }

    private static ColumnComponents.Condenser condenser(Params p) {
        try {
            return ColumnComponents.Condenser.valueOf(p.text("condenser", ColumnComponents.Choices.DEFAULT.condenser().name()));
        } catch (IllegalArgumentException unknown) {
            throw new FlowsheetFailure("unit.setting", "condenser");
        }
    }

    /** The door a calculator's input fills in, its rows numbered from 1 in the input's order: the feed stays behind. */
    public static Params from(V3ColumnInput input, ColumnComponents.Condenser condenser) {
        var door = new JsonObject();
        door.addProperty("package", input.packageId());
        door.addProperty("assay", input.assayId());
        door.addProperty("stages", input.stageCount());
        door.addProperty("feed_stage", input.feedStageNumber());
        door.addProperty("top_pressure", input.topPressurePascal());
        door.addProperty("stage_drop", input.stagePressureDropPascal());
        door.addProperty("diameter", input.columnDiameterMetres());
        door.addProperty("condenser", condenser.name());
        for (var specification : input.specifications()) {
            switch (specification) {
                case V3ColumnSpecification.CondenserOutletTemperature c -> door.addProperty("condenser_temperature", c.kelvin());
                case V3ColumnSpecification.OrganicRefluxRatio r -> door.addProperty("reflux", r.ratio());
                case V3ColumnSpecification.ReboilerDuty d -> door.addProperty("reboiler_duty", d.watts());
            }
        }
        var draws = new JsonArray();
        for (var draw : input.sideDraws()) {
            var row = row(draws);
            row.addProperty("tray", draw.trayNumber());
            row.addProperty("flow", draw.molarFlowMolPerSecond());
        }
        door.add("draws", draws);
        var steam = new JsonArray();
        for (var feed : input.steamFeeds()) {
            var row = row(steam);
            row.addProperty("stage", feed.stageNumber());
            row.addProperty("flow", feed.molarFlowMolPerSecond());
            row.addProperty("temperature", feed.temperatureKelvin());
        }
        door.add("steam", steam);
        var pumparounds = new JsonArray();
        for (var pumparound : input.pumparounds()) {
            var row = row(pumparounds);
            row.addProperty("return_tray", pumparound.returnTray());
            row.addProperty("draw_tray", pumparound.drawTray());
            row.addProperty("duty", pumparound.dutyWatts());
            row.addProperty("split", pumparound.split().name());
        }
        door.add("pumparounds", pumparounds);
        return new Params(door);
    }

    /**
     * {@code door} with {@code template}'s column in it: what the door's own Equipment and Basis settings say stays. The template
     * names no feed. Rows take the old rows' places in order, so a stream on a row's port stays on it and what a row held but a
     * template does not name, a cooler's drop or a pump, stands; rows past the template's go, and their streams wait as open pills.
     */
    public static Params template(Params door, V3ColumnTemplate template) {
        var json = door.json().deepCopy();
        json.addProperty("stages", template.trays() - 2);
        json.addProperty("feed_stage", template.feedTray() - 1);
        json.addProperty("top_pressure", template.topPressure() * 1e5);
        json.addProperty("diameter", template.diameter());
        json.addProperty("condenser_temperature", template.topTemperature());
        json.addProperty("reflux", template.refluxRatio());
        json.addProperty("reboiler_duty", template.reboilerDuty() * 1e6);
        var draws = new JsonArray();
        for (var draw : template.draws()) {
            var row = next(draws, door.rows("draws"));
            row.addProperty("tray", draw.tray() - 1);
            row.addProperty("flow", draw.flow() / 3.6);
        }
        json.add("draws", draws);
        var steam = new JsonArray();
        for (var feed : template.steam()) {
            var row = next(steam, door.rows("steam"));
            row.addProperty("stage", feed.tray() - 1);
            row.addProperty("flow", feed.flow() / 3.6);
            row.addProperty("temperature", feed.temperature());
        }
        json.add("steam", steam);
        var pumparounds = new JsonArray();
        for (var pumparound : template.pumparounds()) {
            var row = next(pumparounds, door.rows("pumparounds"));
            row.addProperty("return_tray", pumparound.returnTray() - 1);
            row.addProperty("draw_tray", pumparound.drawTray() - 1);
            row.addProperty("duty", -pumparound.cooling() * 1e6);
            row.addProperty("split", pumparound.split().name());
        }
        json.add("pumparounds", pumparounds);
        return new Params(json);
    }

    /** The next row of {@code rows}: a copy of the old row in its place, or a new one with an id none of the old rows has. */
    private static JsonObject next(JsonArray rows, List<Params> old) {
        if (rows.size() < old.size()) {
            var row = old.get(rows.size()).json().deepCopy();
            rows.add(row);
            return row;
        }
        int id = rows.size() + 1;
        for (var row : old) {
            try {
                id = Math.max(id, Integer.parseInt(row.text("id", "")) + 1 + rows.size() - old.size());
            } catch (NumberFormatException named) {
                // An id that is no number is no clash.
            }
        }
        var row = new JsonObject();
        row.addProperty("id", String.valueOf(id));
        rows.add(row);
        return row;
    }

    private static JsonObject row(JsonArray rows) {
        var row = new JsonObject();
        row.addProperty("id", String.valueOf(rows.size() + 1));
        rows.add(row);
        return row;
    }

    /** What the calculator's column specifications are for this door. */
    List<V3ColumnSpecification> specifications() {
        return List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature), new V3ColumnSpecification.OrganicRefluxRatio(reflux), new V3ColumnSpecification.ReboilerDuty(reboilerDuty));
    }

    /** The authored pressure of {@code stage} (Pa), which tray hydraulics then march from the column's traffic. */
    double pressure(int stage) {
        return topPressure + (stage - 1) * stageDrop;
    }
}

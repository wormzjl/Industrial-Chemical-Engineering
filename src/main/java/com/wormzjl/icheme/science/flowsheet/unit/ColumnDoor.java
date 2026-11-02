package com.wormzjl.icheme.science.flowsheet.unit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
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
 */
public record ColumnDoor(String packageId, String assayId, int stages, int feedStage, double topPressure, double stageDrop, double diameter,
                         double condenserTemperature, double reflux, double reboilerDuty, ColumnComponents.Condenser condenser, double condenserDrop, double reboilerDrop,
                         List<Draw> draws, List<Steam> steam, List<Pumparound> pumparounds) {
    /** A side draw on {@code tray} (mol/s). */
    public record Draw(String id, int tray, double flow) {
    }

    /** A steam feed into {@code stage} (mol/s, K), which a wired Steam port replaces. */
    public record Steam(String id, int stage, double flow, double temperature) {
    }

    /** A pumparound; {@code drop} is its cooler's cold side (Pa). */
    public record Pumparound(String id, int returnTray, int drawTray, double duty, V3PumparoundSpec.Split split, double drop) {
    }

    public static ColumnDoor of(Params p) {
        var ids = new HashSet<String>();
        var draws = new ArrayList<Draw>();
        for (var row : p.rows("draws")) draws.add(new Draw(id(row, "draw", ids), (int) row.number("tray"), row.number("flow")));
        var steam = new ArrayList<Steam>();
        for (var row : p.rows("steam")) steam.add(new Steam(id(row, "steam", ids), (int) row.number("stage"), row.number("flow"), row.number("temperature")));
        var pumparounds = new ArrayList<Pumparound>();
        for (var row : p.rows("pumparounds")) pumparounds.add(new Pumparound(id(row, "pa", ids), (int) row.number("return_tray"), (int) row.number("draw_tray"), row.number("duty"), split(row), row.number("drop", 0)));
        return new ColumnDoor(p.text("package", ""), p.text("assay", ""), (int) p.number("stages"), (int) p.number("feed_stage"), p.number("top_pressure"), p.number("stage_drop"), p.number("diameter", 0),
                p.number("condenser_temperature"), p.number("reflux"), p.number("reboiler_duty"), condenser(p), p.number("condenser_drop", 0), p.number("reboiler_drop", 0), draws, steam, pumparounds);
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

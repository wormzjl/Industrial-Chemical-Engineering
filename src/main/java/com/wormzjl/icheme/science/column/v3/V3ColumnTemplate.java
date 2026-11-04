package com.wormzjl.icheme.science.column.v3;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.JsonFields;
import java.util.ArrayList;
import java.util.List;

/**
 * A column with no feed and no package (process simulator plan, section 3.7): its trays, set points and connections, as a
 * {@code columnpreset} file holds them, in the units of the file and with trays numbered as the player reads them (the
 * condenser is tray 1). {@code designFeed} is the feed its flows and duties were set for (kmol/h), 0 where it names none:
 * such a template applies as written.
 *
 * @param topTemperature the condenser outlet (K)
 * @param reboilerDuty   MW
 * @param topPressure    bar
 * @param diameter       m
 */
public record V3ColumnTemplate(double designFeed, int trays, int feedTray, double topTemperature, double reboilerDuty,
        double refluxRatio, double topPressure, double diameter, List<Draw> draws, List<Steam> steam, List<Pumparound> pumparounds) {
    /** A side draw (kmol/h). */
    public record Draw(int tray, double flow) {
    }

    /** A steam feed (kmol/h, K). */
    public record Steam(int tray, double flow, double temperature) {
    }

    /** A pumparound; {@code cooling} is the heat it takes out (MW). */
    public record Pumparound(int drawTray, int returnTray, double cooling, V3PumparoundSpec.Split split) {
    }

    public V3ColumnTemplate {
        if (!(designFeed >= 0) || !Double.isFinite(designFeed)) throw new IllegalArgumentException("Invalid design feed");
        if (trays < 2 || trays > 66 || feedTray < 2 || feedTray > trays) throw new IllegalArgumentException("Invalid public tray numbering");
        if (draws.size() > V3ColumnInput.MAX_SIDE_DRAWS || steam.size() > V3ColumnInput.MAX_STEAM_FEEDS || pumparounds.size() > V3ColumnInput.MAX_PUMPAROUNDS)
            throw new IllegalArgumentException("Too many connections");
        for (var d : draws) if (d.tray() < 2 || d.tray() >= trays) throw new IllegalArgumentException("Draw must be on an interior tray");
        for (var s : steam) if (s.tray() < 2 || s.tray() > trays) throw new IllegalArgumentException("Steam must enter below tray 1");
        for (var p : pumparounds)
            if (p.returnTray() < 2 || p.drawTray() <= p.returnTray() || p.drawTray() >= trays) throw new IllegalArgumentException("Invalid PA trays");
        draws = List.copyOf(draws);
        steam = List.copyOf(steam);
        pumparounds = List.copyOf(pumparounds);
    }

    /**
     * This template on {@code feed} kmol/h (decision D4): draws, steam and duties go with the feed; trays, temperatures,
     * pressures, the reflux ratio and the diameter stand. A template with no design feed, or no feed to scale to, is as it is.
     */
    public V3ColumnTemplate scaledTo(double feed) {
        if (!(designFeed > 0) || !(feed > 0) || !Double.isFinite(feed) || feed == designFeed) return this;
        double k = feed / designFeed;
        return new V3ColumnTemplate(feed, trays, feedTray, topTemperature, reboilerDuty * k, refluxRatio, topPressure, diameter,
                draws.stream().map(d -> new Draw(d.tray(), d.flow() * k)).toList(),
                steam.stream().map(s -> new Steam(s.tray(), s.flow() * k, s.temperature())).toList(),
                pumparounds.stream().map(p -> new Pumparound(p.drawTray(), p.returnTray(), p.cooling() * k, p.split())).toList());
    }

    /** The column of a {@code columnpreset} file. Schema 2 held the feed: its flow is the design feed, and its temperature stays behind. */
    public static V3ColumnTemplate read(JsonObject o) {
        String design = JsonFields.integer(o, "schema_version") < 3 ? "feed_flow_kmol_h" : "design_feed_kmol_h";
        var draws = new ArrayList<Draw>();
        for (var e : JsonFields.array(o, "draws")) {
            var d = e.getAsJsonObject();
            draws.add(new Draw(JsonFields.integer(d, "tray"), JsonFields.number(d, "flow_kmol_h")));
        }
        var steam = new ArrayList<Steam>();
        for (var e : JsonFields.array(o, "steam")) {
            var s = e.getAsJsonObject();
            steam.add(new Steam(JsonFields.integer(s, "tray"), JsonFields.number(s, "flow_kmol_h"), JsonFields.number(s, "temperature_kelvin")));
        }
        var pumparounds = new ArrayList<Pumparound>();
        for (var e : JsonFields.array(o, "pumparounds")) {
            var p = e.getAsJsonObject();
            pumparounds.add(new Pumparound(JsonFields.integer(p, "draw_tray"), JsonFields.integer(p, "return_tray"), JsonFields.number(p, "cooling_mw"),
                    V3PumparoundSpec.Split.valueOf(JsonFields.text(p, "split"))));
        }
        return new V3ColumnTemplate(o.has(design) ? JsonFields.number(o, design) : 0, JsonFields.integer(o, "tray_count"), JsonFields.integer(o, "feed_tray"),
                JsonFields.number(o, "top_temperature_kelvin"), JsonFields.number(o, "reboiler_duty_mw"), JsonFields.number(o, "reflux_ratio"),
                JsonFields.number(o, "top_pressure_bar"), JsonFields.number(o, "diameter_m"), draws, steam, pumparounds);
    }

    /** Adds this column's fields to a {@code columnpreset} file. */
    public void write(JsonObject o) {
        o.addProperty("tray_count", trays);
        o.addProperty("feed_tray", feedTray);
        if (designFeed > 0) o.addProperty("design_feed_kmol_h", designFeed);
        o.addProperty("top_temperature_kelvin", topTemperature);
        o.addProperty("reboiler_duty_mw", reboilerDuty);
        o.addProperty("reflux_ratio", refluxRatio);
        o.addProperty("top_pressure_bar", topPressure);
        o.addProperty("diameter_m", diameter);
        var drawRows = new JsonArray();
        for (var d : draws) {
            var row = new JsonObject();
            row.addProperty("tray", d.tray());
            row.addProperty("flow_kmol_h", d.flow());
            drawRows.add(row);
        }
        var steamRows = new JsonArray();
        for (var s : steam) {
            var row = new JsonObject();
            row.addProperty("tray", s.tray());
            row.addProperty("flow_kmol_h", s.flow());
            row.addProperty("temperature_kelvin", s.temperature());
            steamRows.add(row);
        }
        var paRows = new JsonArray();
        for (var p : pumparounds) {
            var row = new JsonObject();
            row.addProperty("draw_tray", p.drawTray());
            row.addProperty("return_tray", p.returnTray());
            row.addProperty("cooling_mw", p.cooling());
            row.addProperty("split", p.split().name());
            paRows.add(row);
        }
        o.add("draws", drawRows);
        o.add("steam", steamRows);
        o.add("pumparounds", paRows);
    }
}

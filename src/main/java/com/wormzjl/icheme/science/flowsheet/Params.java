package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A door's settings as the flowsheet file holds them (batch 2026-11-02-process-simulator, plan section 3.9): SI values under
 * stable keys. The JSON stays whole, so a field this version does not know is kept and written back. A setting that is
 * missing or of the wrong kind refuses the unit in the run, not the sheet.
 */
public record Params(JsonObject json) {
    public static Params none() {
        return new Params(new JsonObject());
    }

    public Params {
        Objects.requireNonNull(json, "json");
    }

    private JsonElement value(String key) {
        var value = json.get(key);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static boolean numeric(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    /** The number under {@code key}. */
    public double number(String key) {
        var value = value(key);
        if (value == null || !numeric(value) || !Double.isFinite(value.getAsDouble())) throw new FlowsheetFailure("unit.setting", key);
        return value.getAsDouble();
    }

    public double number(String key, double fallback) {
        return value(key) == null ? fallback : number(key);
    }

    public int integer(String key, int fallback) {
        return value(key) == null ? fallback : (int) number(key);
    }

    public String text(String key, String fallback) {
        var value = value(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new FlowsheetFailure("unit.setting", key);
        return value.getAsString();
    }

    /** The rows under {@code key}, each with its own settings; none if the key is absent. */
    public List<Params> rows(String key) {
        var value = value(key);
        if (value == null) return List.of();
        if (!value.isJsonArray()) throw new FlowsheetFailure("unit.setting", key);
        var rows = new ArrayList<Params>();
        for (var row : value.getAsJsonArray()) {
            if (!row.isJsonObject()) throw new FlowsheetFailure("unit.setting", key);
            rows.add(new Params(row.getAsJsonObject()));
        }
        return rows;
    }
}

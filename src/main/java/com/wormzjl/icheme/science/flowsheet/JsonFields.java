package com.wormzjl.icheme.science.flowsheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** One object of the flowsheet file as it is read. A reader takes its fields out, each checked, so what stays is what a later version added. */
final class JsonFields {
    final JsonObject rest;
    final String path;

    JsonFields(JsonElement element, String path) {
        if (!element.isJsonObject()) throw damaged(path);
        rest = element.getAsJsonObject();
        this.path = path;
    }

    private @Nullable JsonElement take(String key) {
        var value = rest.remove(key);
        return value == null || value.isJsonNull() ? null : value;
    }

    private JsonElement need(String key) {
        var value = take(key);
        if (value == null) throw damaged(at(key));
        return value;
    }

    private String at(String key) {
        return path + "." + key;
    }

    private static boolean isText(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    String text(String key) {
        var value = need(key);
        if (!isText(value)) throw damaged(at(key));
        return value.getAsString();
    }

    /** A text that names something, so it cannot be empty. */
    String id(String key) {
        String id = text(key);
        if (id.isEmpty()) throw damaged(at(key));
        return id;
    }

    /** Text rows that name something once each. */
    List<String> texts(String key) {
        var value = need(key);
        if (!value.isJsonArray()) throw damaged(at(key));
        var texts = new ArrayList<String>();
        for (var row : value.getAsJsonArray()) {
            if (!isText(row) || texts.contains(row.getAsString())) throw damaged(at(key));
            texts.add(row.getAsString());
        }
        return texts;
    }

    private double finite(String key, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble())) throw damaged(at(key));
        return value.getAsDouble();
    }

    double number(String key) {
        return finite(key, need(key));
    }

    double number(String key, double fallback) {
        var value = take(key);
        return value == null ? fallback : finite(key, value);
    }

    /** A flow: a number that cannot be under zero. */
    double amount(String key) {
        double amount = number(key);
        if (amount < 0) throw damaged(at(key));
        return amount;
    }

    private int whole(String key, JsonElement value) {
        double number = finite(key, value);
        if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) throw damaged(at(key));
        return (int) number;
    }

    int whole(String key) {
        return whole(key, need(key));
    }

    int whole(String key, int fallback) {
        var value = take(key);
        return value == null ? fallback : whole(key, value);
    }

    boolean bool(String key) {
        var value = need(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw damaged(at(key));
        return value.getAsBoolean();
    }

    /** A positive number, null if absent. */
    @Nullable Double optionalAmount(String key) {
        var value = take(key);
        if (value == null) return null;
        double amount = finite(key, value);
        if (!(amount > 0)) throw damaged(at(key));
        return amount;
    }

    /** An enum constant by its name; a name this version does not know is damage. */
    <E extends Enum<E>> E name(String key, Class<E> type) {
        String name = text(key);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            throw damaged(at(key));
        }
    }

    <E extends Enum<E>> @Nullable E name(String key, Class<E> type, @Nullable E fallback) {
        var value = rest.get(key);
        if (value != null && !value.isJsonNull()) return name(key, type);
        rest.remove(key);
        return fallback;
    }

    JsonFields object(String key) {
        return new JsonFields(need(key), at(key));
    }

    @Nullable JsonFields optionalObject(String key) {
        var value = take(key);
        return value == null ? null : new JsonFields(value, at(key));
    }

    private List<JsonFields> rows(String key, JsonElement value) {
        if (!value.isJsonArray()) throw damaged(at(key));
        var rows = new ArrayList<JsonFields>();
        for (var row : value.getAsJsonArray()) rows.add(new JsonFields(row, at(key) + "[" + rows.size() + "]"));
        return rows;
    }

    List<JsonFields> rows(String key) {
        return rows(key, need(key));
    }

    List<JsonFields> optionalRows(String key) {
        var value = take(key);
        return value == null ? List.of() : rows(key, value);
    }

    static FlowsheetFailure damaged(String where) {
        return new FlowsheetFailure("file.damaged", where);
    }

    /** {@code json} with what a later version put beside its fields. */
    static JsonObject beside(JsonObject json, JsonObject unknown) {
        for (var field : unknown.entrySet()) json.add(field.getKey(), field.getValue());
        return json;
    }
}

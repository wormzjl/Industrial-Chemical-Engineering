package com.wormzjl.icheme.science.tutorial.guide;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.films.Films;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads the guide's file, {@code assets/icheme/tutorial/guide.json}: a client asset, so no compatibility work. It refuses
 * an unknown field, a duplicate id, an unknown media name and a term whose step is not in the guide.
 *
 * <pre>
 * { "terms": { "still": "welcome/film" },
 *   "chapters": [ { "id": "welcome", "steps": [
 *       { "id": "guide" },
 *       { "id": "units", "media": "units" },
 *       { "id": "water", "media": "read" },
 *       { "id": "dots", "media": "card:three_parts" },
 *       { "id": "film", "media": "film:still_inside" },
       { "id": "tower", "media": "page:equipment_column" },
 *       { "id": "build", "check": "server", "criterion": "equipment_built" } ] } ] }
 * </pre>
 *
 * A step's check is {@code client} where it names none.
 */
public final class GuideFile {
    public static final String PATH = "/assets/icheme/tutorial/guide.json";

    private GuideFile() {
    }

    /** The guide on the class path (the mod's jar; the tests' and the render tool's class path). */
    public static Guide load() {
        try (InputStream in = GuideFile.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("No " + PATH);
            return read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + PATH, e);
        }
    }

    /** The guide in {@code json}, its films and cards checked against {@code Films} and {@code Cards}. */
    public static Guide read(JsonObject json) {
        var films = new java.util.HashSet<String>();
        Films.names().forEach(films::add);
        return read(json, films::contains, Set.copyOf(Cards.names())::contains);
    }

    public static Guide read(JsonObject json, Predicate<String> films, Predicate<String> cards) {
        only(json, "guide", "terms", "chapters");
        var chapters = new ArrayList<Guide.Chapter>();
        for (var element : json.getAsJsonArray("chapters")) {
            var c = object(element, "chapter");
            only(c, "chapter", "id", "steps");
            String id = c.get("id").getAsString();
            var steps = new ArrayList<Guide.Step>();
            for (var stepElement : c.getAsJsonArray("steps")) {
                var s = object(stepElement, "step");
                only(s, "step " + id, "id", "media", "check", "criterion");
                var check = s.has("check") ? check(s.get("check").getAsString()) : Guide.Check.CLIENT;
                steps.add(new Guide.Step(s.get("id").getAsString(), media(s.has("media") ? s.get("media").getAsString() : null, films, cards), check,
                        s.has("criterion") ? s.get("criterion").getAsString() : null));
            }
            chapters.add(new Guide.Chapter(id, steps));
        }
        var terms = new LinkedHashMap<String, String>();
        if (json.has("terms"))
            for (var e : json.getAsJsonObject("terms").entrySet()) {
                if (!Guide.ID.matcher(e.getKey()).matches()) throw new IllegalArgumentException("Bad term id: " + e.getKey());
                terms.put(e.getKey(), e.getValue().getAsString());
            }
        var guide = new Guide(chapters, terms);
        for (var e : terms.entrySet())
            if (guide.step(e.getValue()) == null) throw new IllegalArgumentException("Term " + e.getKey() + " names no step: " + e.getValue());
        return guide;
    }

    private static Guide.Check check(String text) {
        for (var check : Guide.Check.values()) if (check.key().equals(text)) return check;
        throw new IllegalArgumentException("Unknown check: " + text);
    }

    private static Guide.Media media(String text, Predicate<String> films, Predicate<String> cards) {
        if (text == null) return Guide.Media.NONE;
        if (text.equals("units")) return Guide.Media.UNITS;
        if (text.equals("read")) return Guide.Media.READ;
        if (text.startsWith("page:")) {
            // A page by its id alone (pages.json is read after the guide: its tours name the guide's steps).
            String id = text.substring(5);
            if (com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo.kindOf(id) == null && !PageFile.ITEMS.contains(id)) throw new IllegalArgumentException("Unknown page: " + id);
            return new Guide.Page(id);
        }
        if (text.startsWith("film:")) {
            String name = text.substring(5);
            if (!films.test(name)) throw new IllegalArgumentException("Unknown film: " + name);
            return new Guide.Film(name);
        }
        if (text.startsWith("card:")) {
            String name = text.substring(5);
            if (!cards.test(name)) throw new IllegalArgumentException("Unknown card: " + name);
            return new Guide.Card(name);
        }
        throw new IllegalArgumentException("Unknown media: " + text);
    }

    static JsonObject object(JsonElement element, String what) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("Not an object: " + what);
        return element.getAsJsonObject();
    }

    /** Refuses a field of {@code json} not in {@code fields}. */
    static void only(JsonObject json, String what, String... fields) {
        var known = Set.of(fields);
        for (var key : json.keySet()) if (!known.contains(key)) throw new IllegalArgumentException("Unknown field in " + what + ": " + key);
    }
}

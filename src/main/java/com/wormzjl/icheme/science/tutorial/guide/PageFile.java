package com.wormzjl.icheme.science.tutorial.guide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import com.wormzjl.icheme.science.tutorial.films.Films;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads the equipment pages' file, {@code assets/icheme/tutorial/pages.json} (batch 2026-11-12-tutorial-system, P5): a client
 * asset, so no compatibility work. Every kind's item id holds an entry (an empty one for the header only); another item's id
 * holds one where its page is wanted. It refuses an unknown field, an id that is neither a kind's item nor one of
 * {@link #ITEMS}, a missing kind, an unknown film or tour, and a unit list that names a page twice or none.
 *
 * <pre>
 * { "unit": [ "equipment_pump", "pressure_control_valve" ],
 *   "pages": { "equipment_pump": { "film": "unit_pump" }, "equipment_batch_still": { "tour": "fluid_device.still" },
 *              "pressure_control_valve": { }, ... } }
 * </pre>
 *
 * A film is read by its name only: no film body is built until its page's Watch is pressed.
 */
public final class PageFile {
    public static final String PATH = "/assets/icheme/tutorial/pages.json";
    /** The mod's other items a page may be about: the control valve (the crude unit's V-101, which has no equipment body). */
    public static final Set<String> ITEMS = Set.of("pressure_control_valve");

    private PageFile() {
    }

    /** The pages on the class path. */
    public static Pages load() {
        return load(TourFile.load().keySet()::contains);
    }

    /** The pages on the class path, their tours checked by {@code tours} (the client's tours, read once). */
    public static Pages load(Predicate<String> tours) {
        try (InputStream in = PageFile.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("No " + PATH);
            return read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), Films::has, tours);
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + PATH, e);
        }
    }

    /** The pages in {@code json}, their films checked against {@code Films} and their tours against {@code tours.json}. */
    public static Pages read(JsonObject json) {
        return read(json, Films::has, TourFile.load().keySet()::contains);
    }

    public static Pages read(JsonObject json, Predicate<String> films, Predicate<String> tours) {
        GuideFile.only(json, "pages", "unit", "pages");
        var pages = new LinkedHashMap<String, Pages.Page>();
        for (var e : json.getAsJsonObject("pages").entrySet()) {
            String id = e.getKey();
            var p = GuideFile.object(e.getValue(), "page " + id);
            GuideFile.only(p, "page " + id, "film", "tour");
            String film = p.has("film") ? p.get("film").getAsString() : null, tour = p.has("tour") ? p.get("tour").getAsString() : null;
            if (film != null && !films.test(film)) throw new IllegalArgumentException("Unknown film: " + film);
            if (tour != null && !tours.test(tour)) throw new IllegalArgumentException("Unknown tour: " + tour);
            var kind = EquipmentInfo.kindOf(id);
            if (kind != null) pages.put(id, Pages.Page.of(kind, film, tour));
            else if (ITEMS.contains(id)) pages.put(id, Pages.Page.item(id, film, tour));
            else throw new IllegalArgumentException("Not a page: " + id);
        }
        for (var kind : EquipmentShape.Kind.values())
            if (!pages.containsKey(EquipmentInfo.itemId(kind))) throw new IllegalArgumentException("No page for " + EquipmentInfo.itemId(kind));
        var unit = new ArrayList<String>();
        for (var element : json.getAsJsonArray("unit")) unit.add(element.getAsString());
        return new Pages(pages, unit);
    }
}

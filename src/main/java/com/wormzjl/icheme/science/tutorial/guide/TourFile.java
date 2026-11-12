package com.wormzjl.icheme.science.tutorial.guide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.films.Films;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the help tours, {@code assets/icheme/tutorial/tours.json}: page name to {@code {"film": name?, "stops": [{"anchor":
 * key, "wait": bool?}]}}. A client asset, so no compatibility work; it refuses an unknown field, an unknown film and a
 * tour of more than {@link Tour#MAX_STOPS} stops.
 */
public final class TourFile {
    public static final String PATH = "/assets/icheme/tutorial/tours.json";

    private TourFile() {
    }

    /** The tours on the class path, by page. */
    public static Map<String, Tour> load() {
        try (InputStream in = TourFile.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("No " + PATH);
            return read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + PATH, e);
        }
    }

    public static Map<String, Tour> read(JsonObject json) {
        var tours = new LinkedHashMap<String, Tour>();
        for (var e : json.entrySet()) {
            var t = GuideFile.object(e.getValue(), "tour " + e.getKey());
            GuideFile.only(t, "tour " + e.getKey(), "film", "stops");
            String film = t.has("film") ? t.get("film").getAsString() : null;
            if (film != null && Films.build(film) == null) throw new IllegalArgumentException("Unknown film: " + film);
            var stops = new ArrayList<Tour.Stop>();
            for (var element : t.getAsJsonArray("stops")) {
                var s = GuideFile.object(element, "stop of " + e.getKey());
                GuideFile.only(s, "stop of " + e.getKey(), "anchor", "wait");
                stops.add(new Tour.Stop(s.get("anchor").getAsString(), s.has("wait") && s.get("wait").getAsBoolean()));
            }
            tours.put(e.getKey(), new Tour(e.getKey(), stops, film));
        }
        return java.util.Collections.unmodifiableMap(tours);
    }
}

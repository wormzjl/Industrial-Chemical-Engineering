package com.wormzjl.icheme.science.tutorial.guide;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

/**
 * Reads the photographs' manifest, {@code assets/icheme/tutorial/photos.json} (batch 2026-11-18-tutorial-photos), written by
 * {@code tools/tutorial-photos/PhotoAssets.java}: a client asset, so no compatibility work. It refuses an unknown field.
 *
 * <pre>
 * { "welcome": { "w": 384, "h": 256, "fx": 0.45, "fy": 0.55 }, ... }
 * </pre>
 */
public final class PhotoFile {
    public static final String PATH = "/assets/icheme/tutorial/photos.json";

    private PhotoFile() {
    }

    /** The photos on the class path. */
    public static Photos load() {
        try (InputStream in = PhotoFile.class.getResourceAsStream(PATH)) {
            if (in == null) throw new IllegalStateException("No " + PATH);
            return read(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable " + PATH, e);
        }
    }

    public static Photos read(JsonObject json) {
        var photos = new LinkedHashMap<String, Photos.Photo>();
        for (var e : json.entrySet()) {
            var p = GuideFile.object(e.getValue(), "photo " + e.getKey());
            GuideFile.only(p, "photo " + e.getKey(), "w", "h", "fx", "fy");
            photos.put(e.getKey(), new Photos.Photo(e.getKey(), p.get("w").getAsInt(), p.get("h").getAsInt(), p.get("fx").getAsDouble(), p.get("fy").getAsDouble()));
        }
        return new Photos(photos);
    }
}

package com.wormzjl.icheme.science.tutorial.guide;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The field guide's photographs (batch 2026-11-18-tutorial-photos), read from {@code assets/icheme/tutorial/photos.json}
 * ({@link PhotoFile}): each photo's PNG size and its thumbnail's focus. A chapter names its photos in {@code guide.json}.
 * The caption's and name's language keys and the texture are derived from the id, never stored. Credits ship in
 * {@code assets/icheme/tutorial/PHOTO_CREDITS.md}. No Minecraft type.
 */
public record Photos(Map<String, Photo> photos) {
    public static final String KEY = "tutorial.icheme.photo.";

    /** A photo: its id, its PNG's size in pixels, and the point a thumbnail is cut round, as shares of its width and height. */
    public record Photo(String id, int w, int h, double fx, double fy) {
        public Photo {
            if (!Guide.ID.matcher(id).matches()) throw new IllegalArgumentException("Bad photo id: " + id);
            if (w <= 0 || h <= 0) throw new IllegalArgumentException("Bad photo size: " + id);
            if (!(fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1)) throw new IllegalArgumentException("Bad photo focus: " + id);
        }

        /** The caption's language key: what the photo shows, then its author. */
        public String captionKey() {
            return KEY + id;
        }

        /** The short name on the viewer's key, where a chapter has several photos. */
        public String nameKey() {
            return KEY + id + ".name";
        }

        /** The texture's resource name. */
        public String texture() {
            return "icheme:textures/tutorial/photo/" + id + ".png";
        }
    }

    public Photos {
        photos = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(photos));
    }

    /** The photo {@code id}, or null. */
    public @Nullable Photo photo(String id) {
        return photos.get(id);
    }
}

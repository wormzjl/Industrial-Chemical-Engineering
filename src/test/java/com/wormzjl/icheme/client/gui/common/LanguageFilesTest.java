package com.wormzjl.icheme.client.gui.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every translation holds en_us's keys and fills the same arguments (batch 2026-11-03-localization). A missing line shows
 * English, so a key the English gains and a translation lacks goes unnoticed in game; a translation whose arguments differ
 * shows the wrong value, or the raw line (Minecraft's {@code TranslatableContents} keeps a line whole when a specifier is
 * not {@code %s} or {@code %%}, or names an argument that is not there).
 */
class LanguageFilesTest {
    /** Minecraft's specifier pattern ({@code TranslatableContents.FORMAT_PATTERN}). */
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    private static Path folder() throws URISyntaxException {
        var english = Objects.requireNonNull(LanguageFilesTest.class.getResource("/assets/icheme/lang/en_us.json"));
        return Path.of(english.toURI()).getParent();
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /**
     * The arguments a line shows, as Minecraft reads it ({@code TranslatableContents.decomposeTemplate}): each {@code %s}'s
     * index, in order. A {@code %} that is neither {@code %s} nor {@code %%} (a specifier like {@code %d}, or a lone sign as in
     * "Mass %") keeps the whole line as written, which shows none: harmless for a line that takes no arguments.
     */
    static List<Integer> arguments(String line) {
        var out = new ArrayList<Integer>();
        var m = FORMAT.matcher(line);
        int next = 0, end = 0;
        while (m.find()) {
            if (line.substring(end, m.start()).indexOf('%') >= 0) return List.of();
            end = m.end();
            if (m.group().equals("%%")) continue;
            if (!m.group(2).equals("s")) return List.of();
            out.add(m.group(1) == null ? next++ : Integer.parseInt(m.group(1)) - 1);
        }
        if (line.substring(end).indexOf('%') >= 0) return List.of();
        out.sort(null);
        return out;
    }

    @Test
    void everyTranslationHasTheEnglishKeysAndArguments() throws IOException, URISyntaxException {
        var folder = folder();
        var english = read(folder.resolve("en_us.json"));
        var problems = new TreeMap<String, List<String>>();
        int languages = 0;
        try (var files = Files.list(folder)) {
            for (var file : files.filter(f -> f.toString().endsWith(".json") && !f.endsWith("en_us.json")).sorted().toList()) {
                languages++;
                var lang = read(file);
                var found = new ArrayList<String>();
                var missing = new TreeSet<>(english.keySet());
                missing.removeAll(lang.keySet());
                var extra = new TreeSet<>(lang.keySet());
                extra.removeAll(english.keySet());
                missing.forEach(k -> found.add("missing " + k));
                extra.forEach(k -> found.add("not in en_us " + k));
                for (var key : lang.keySet()) {
                    if (!english.has(key)) continue;
                    String en = english.get(key).getAsString(), tr = lang.get(key).getAsString();
                    if (tr.isBlank() && !en.isBlank()) found.add("empty " + key);
                    if (!arguments(en).equals(arguments(tr))) found.add("arguments " + key + " " + arguments(en) + " vs " + arguments(tr));
                    if (en.split("\n", -1).length != tr.split("\n", -1).length) found.add("line breaks " + key);
                }
                String text = Files.readString(file, StandardCharsets.UTF_8);
                long entries = Pattern.compile("^\\s*\"[^\"]+\"\\s*:", Pattern.MULTILINE).matcher(text).results().count();
                if (entries != lang.size()) found.add((entries - lang.size()) + " keys defined twice");
                if (!found.isEmpty()) problems.put(file.getFileName().toString(), found);
            }
        }
        assertTrue(languages > 0, "no translation next to en_us.json in " + folder);
        assertEquals(new TreeMap<String, List<String>>(), problems);
    }

    @Test
    void argumentsAreReadAsMinecraftReadsThem() {
        assertEquals(List.of(0, 1), arguments("%s m × %s m"));
        assertEquals(List.of(0, 1), arguments("%2$s, %1$s m³"));
        assertEquals(List.of(0), arguments("Worst flood: %s%%"));
        assertEquals(List.of(), arguments("%d trays"));
        assertEquals(List.of(), arguments("Mass %"));
        assertEquals(List.of(), arguments("%s at 50 % load"));
        assertEquals(List.of(0, 0), arguments("%1$s, then %1$s"));
    }
}

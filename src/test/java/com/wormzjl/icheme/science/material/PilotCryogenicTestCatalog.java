package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The bundled material resources with the pilot network selected (batch 2026-09-24-coolprop-low-temperature, P3 WP3,
 * plan {@code P3_PILOT_ENGINE_PLAN.md} section 1.3). Since P3 the pilot package {@code icheme:pilot_cryogenic} and
 * its records (reference spines, CO2-I crystal, CarbonDioxide, the {@code pilot_*} property records, the E-PPR78
 * interactions) are bundled in the main resources; this catalog adds only what a test selection needs, listed in
 * {@code /materials/pilot-cryogenic/index.json}: an override of the resource key
 * {@code data/icheme/materials/networks/default.json} naming the pilot package ({@code MaterialPresets} allows one
 * network configuration), and component fluid presets for methane and ethane (CO2's is bundled since O11 of batch
 * 2026-11-04-open-thermo-data). The override files live outside
 * {@code data/} on the test classpath, so the bundled catalog of every other test is not shadowed.
 */
public final class PilotCryogenicTestCatalog {
    public static final String PACKAGE_ID = "icheme:pilot_cryogenic";
    public static final String NETWORK = "data/icheme/materials/networks/default.json";
    private static final String PREFIX = "materials/pilot-cryogenic/";
    private static final Map<String, String> RESOURCES = load();
    private static final MaterialCatalog CATALOG = MaterialCatalog.parse(RESOURCES);

    private PilotCryogenicTestCatalog() {}

    /** The bundled catalog with the pilot network selected. */
    public static MaterialCatalog catalog() { return CATALOG; }

    /** A copy of the resource set, for tests that edit one record and parse again. */
    public static Map<String, String> resources() { return new LinkedHashMap<>(RESOURCES); }

    /** Parses the set with one record edited as JSON. */
    public static MaterialCatalog parseWith(String path, Consumer<JsonObject> edit) { return parseWith(Map.of(path, edit)); }

    /** Parses the set with each record of {@code edits} edited as JSON. */
    public static MaterialCatalog parseWith(Map<String, Consumer<JsonObject>> edits) {
        var resources = resources();
        edits.forEach((path, edit) -> {
            String text = resources.get(path);
            if (text == null) throw new IllegalArgumentException("No pilot resource " + path);
            JsonObject record = JsonParser.parseString(text).getAsJsonObject();
            edit.accept(record);
            resources.put(path, record.toString());
        });
        return MaterialCatalog.parse(resources);
    }

    /** Appends {@code -edited} to a record's {@code revision}: the bump a numerical edit declares (owner decision 2026-10-26). */
    public static void bump(JsonObject record) { record.addProperty("revision", record.get("revision").getAsString() + "-edited"); }

    private static Map<String, String> load() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        if (!resources.containsKey(NETWORK)) throw new IllegalStateException("The bundled catalog has no " + NETWORK + " to override");
        for (var entry : JsonParser.parseString(read(PREFIX + "index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            String text = read(PREFIX + path);
            // The network record replaces the bundled one; every other entry is new.
            if (resources.put(path, text) != null && !path.equals(NETWORK)) throw new IllegalArgumentException("Duplicate pilot resource " + path);
        }
        return Map.copyOf(resources);
    }

    static String read(String path) {
        try (var stream = PilotCryogenicTestCatalog.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("Missing pilot resource " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}

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
 * The pilot cryogenic package {@code icheme:pilot_cryogenic} (batch 2026-09-24-coolprop-low-temperature, P3 WP3, plan
 * {@code P3_PILOT_ENGINE_PLAN.md} section 1.3) over the bundled material resources. Since O5 of batch
 * 2026-11-04-open-thermo-data (owner decision E25: once CO2 is in the world list the pilot is a test reference only) its
 * records live in this test overlay, {@code /materials/pilot-cryogenic/index.json}: the package, its method, the E-PPR78
 * interactions, the four {@code pilot_*} property records and the four reference spines (the E-PPR78 group matrix
 * {@code icheme:eppr78_2022} is bundled again since W2a of batch 2026-11-11-aqueous-solvents), with the
 * CarbonDioxide compound record naming {@code icheme:pilot_carbon_dioxide} as its default dataset again (the game's names
 * its own, {@code icheme:chemsep_carbon_dioxide}; the pilot package lists CarbonDioxide by that default). The CO2-I crystal
 * stays bundled: {@code icheme:refinery} declares it. {@link #withPilot()} is the bundled catalog with these records and
 * the game's network; {@link #catalog()} also selects the pilot network: an override of the resource key
 * {@code data/icheme/materials/networks/default.json} naming the pilot package ({@code MaterialPresets} allows one network
 * configuration), and component fluid presets for methane and ethane (CO2's is bundled since O11). The overlay lives
 * outside {@code data/} on the test classpath, so the bundled catalog of every other test is the game's.
 */
public final class PilotCryogenicTestCatalog {
    public static final String PACKAGE_ID = "icheme:pilot_cryogenic";
    public static final String NETWORK = "data/icheme/materials/networks/default.json";
    /** The bundled compound record the overlay replaces: its default dataset is the pilot's. */
    static final String CARBON_DIOXIDE = "data/icheme/materials/components/carbon_dioxide.json";
    private static final String PREFIX = "materials/pilot-cryogenic/";
    private static final Map<String, String> RECORDS = load(false);
    private static final MaterialCatalog WITH_PILOT = MaterialCatalog.parse(RECORDS);
    private static final Map<String, String> RESOURCES = load(true);
    private static final MaterialCatalog CATALOG = MaterialCatalog.parse(RESOURCES);

    private PilotCryogenicTestCatalog() {}

    /** The bundled catalog with the pilot network selected. */
    public static MaterialCatalog catalog() { return CATALOG; }

    /** The bundled catalog with the pilot package's records added; the network is the game's. */
    public static MaterialCatalog withPilot() { return WITH_PILOT; }

    /** A copy of {@link #withPilot()}'s resource set. */
    public static Map<String, String> withPilotResources() { return new LinkedHashMap<>(RECORDS); }

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

    /**
     * {@code base} (a resource set that holds the bundled records, such as another test overlay's) with the pilot records
     * added and the CarbonDioxide record replaced; the network and the presets stay {@code base}'s.
     */
    public static Map<String, String> over(Map<String, String> base) { return load(base, false); }

    /** The bundled resources with the pilot records, and with the pilot network and its presets when {@code selected}. */
    private static Map<String, String> load(boolean selected) { return load(MaterialCatalog.bundled().resources(), selected); }

    private static Map<String, String> load(Map<String, String> base, boolean selected) {
        var resources = new LinkedHashMap<>(base);
        if (!resources.containsKey(NETWORK)) throw new IllegalStateException("The bundled catalog has no " + NETWORK + " to override");
        for (var entry : JsonParser.parseString(read(PREFIX + "index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            if (!selected && (path.equals(NETWORK) || path.contains("/presets/"))) continue;
            String text = read(PREFIX + path);
            // The network and the CarbonDioxide record replace the bundled ones; every other entry is new.
            if (resources.put(path, text) != null && !path.equals(NETWORK) && !path.equals(CARBON_DIOXIDE)) throw new IllegalArgumentException("Duplicate pilot resource " + path);
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

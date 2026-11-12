package com.wormzjl.icheme.science.material;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The pilot aqueous package {@code icheme:pilot_aqueous} (batch 2026-11-11-aqueous-solvents, W2b) over the bundled
 * material resources, from the test overlay {@code /materials/pilot-aqueous/index.json}: water as a compound of the
 * cubic (the main record {@code icheme:cubic_water} since W3b step 2: IAPWS-95 critical point, acentric factor 0.3443, a
 * Mathias-Copeman alpha, the water model's steam Cp, Dortmund and E-PPR78 counts H2O 1), ethanol from ChemSep with its homomorph propane
 * (the main record {@code icheme:chemsep_ethanol} since W6, compound record {@code Ethanol}; the overlay's pilot record had the same
 * phase data), the method {@code icheme:pilot_aqueous_pr78} (the
 * crude_pr78 pair libraries) and the package with n-hexane, benzene, propane, methane, nitrogen, CO2, hydrogen and the
 * cut crude_pc03. The overlay holds the method and the package only (W6 moved its ethanol to main data); it lives outside
 * {@code data/}, so every other test sees the game's catalog.
 */
public final class PilotAqueousTestCatalog {
    public static final String PACKAGE_ID = "icheme:pilot_aqueous";
    private static final String PREFIX = "materials/pilot-aqueous/";
    private static final Map<String, String> RESOURCES = load();
    private static final MaterialCatalog CATALOG = MaterialCatalog.parse(RESOURCES);

    private PilotAqueousTestCatalog() {}

    /** The bundled catalog with the pilot records added. */
    public static MaterialCatalog catalog() { return CATALOG; }

    /** A copy of the resource set. */
    public static Map<String, String> resources() { return new LinkedHashMap<>(RESOURCES); }

    /** Parses the set with one record (bundled or pilot) edited as JSON: a fresh catalog with its own pair cache. */
    public static MaterialCatalog parseWith(String path, Consumer<JsonObject> edit) {
        var resources = resources();
        String text = resources.get(path);
        if (text == null) throw new IllegalArgumentException("No resource " + path);
        JsonObject record = JsonParser.parseString(text).getAsJsonObject();
        edit.accept(record);
        resources.put(path, record.toString());
        return MaterialCatalog.parse(resources);
    }

    private static Map<String, String> load() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (var entry : JsonParser.parseString(PilotCryogenicTestCatalog.read(PREFIX + "index.json")).getAsJsonArray()) {
            String path = entry.getAsString();
            if (resources.put(path, PilotCryogenicTestCatalog.read(PREFIX + path)) != null) throw new IllegalArgumentException("Duplicate pilot resource " + path);
        }
        return Map.copyOf(resources);
    }
}

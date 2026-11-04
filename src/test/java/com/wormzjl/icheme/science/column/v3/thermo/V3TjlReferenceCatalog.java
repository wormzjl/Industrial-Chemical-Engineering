package com.wormzjl.icheme.science.column.v3.thermo;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Rebuilds only the independent old numerical oracle for frozen stalled/wet-state regressions.
 *
 * <p>The frozen TJL19 and TJL20-methane tables live in {@code column/v3/tjl-reference-resources.json}: the
 * component, property, package and assay records, in the order they replace or extend the bundled catalog's
 * resources. They were written from the former Java package tables (removed 2026-10-15) and carry the same
 * numbers.</p>
 */
public final class V3TjlReferenceCatalog {
    private static final String RECORDS = "/column/v3/tjl-reference-resources.json";

    private V3TjlReferenceCatalog() {}

    public static MaterialCatalog catalog() {
        var resources=new LinkedHashMap<>(MaterialRuntime.current().resources());
        resources.keySet().removeIf(key->key.contains("/networks/")||key.contains("/presets/fluid_")); // Independent column-only numerical oracle.
        // The old packages below replace the bundled ones, so the bundled assays (regrouped on the bundled compounds) leave
        // with them, and so do the packages that extend the bundled TJL20 package (tjl20_methane_nitrogen with its column
        // preset, and the network package icheme:refinery, W6). The
        // reference set brings its own two assays.
        resources.keySet().removeIf(key->key.contains("/assays/")||key.endsWith("/packages/tjl20_nitrogen.json")||key.endsWith("/packages/refinery.json")
                ||key.endsWith("/presets/column_tia_juana_nitrogen.json"));
        try(var stream=Objects.requireNonNull(V3TjlReferenceCatalog.class.getResourceAsStream(RECORDS),RECORDS);
                var reader=new InputStreamReader(stream,StandardCharsets.UTF_8)) {
            for(var entry:JsonParser.parseReader(reader).getAsJsonArray()) {
                var record=entry.getAsJsonObject();
                resources.put(record.get("path").getAsString(),record.get("record").toString());
            }
        } catch(IOException e) {
            throw new UncheckedIOException(e);
        }
        // Keep the old operating conditions with the independent old thermodynamic tables.
        var json=new Gson();String root="data/icheme/materials/";
        for(String presetPath:List.of(root+"presets/column_literature.json",root+"presets/column_tia_juana.json")) {
            var preset=JsonParser.parseString(resources.get(presetPath)).getAsJsonObject();
            preset.getAsJsonObject("operating").add("sideDraws",json.toJsonTree(List.of(
                    Map.of("trayNumber",10,"molarFlowMolPerSecond",491./3.6),Map.of("trayNumber",18,"molarFlowMolPerSecond",515./3.6),Map.of("trayNumber",28,"molarFlowMolPerSecond",165./3.6))));
            resources.put(presetPath,preset.toString());
        }
        return MaterialCatalog.parse(resources);
    }
}

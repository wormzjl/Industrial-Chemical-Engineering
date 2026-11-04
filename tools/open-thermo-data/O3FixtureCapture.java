package com.wormzjl.icheme.science.column.v3;

import com.google.gson.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.nio.file.*;
import org.junit.jupiter.api.Test;

/**
 * Throwaway O3 capture (batch 2026-11-04-open-thermo-data): the column qualification inputs of the four grades
 * (V3TestInputs.crude, as a player builds them from the client templates) and the classical draw volumes in the
 * prescribed-drop mode, written to tmp/open-thermo-o3/capture/. Copy to src/test/java/com/wormzjl/icheme/science/column/v3/,
 * run ./gradlew columnGates --offline --tests '*O3FixtureCapture', move it back.
 */
class O3FixtureCapture {
    private static final String[] GRADES = {"light_crude", "medium_crude", "heavy_crude", "oil_sand_dilbit"};
    @Test void capture() throws Exception {
        var catalog = MaterialCatalog.bundled();
        var gson = new GsonBuilder().setPrettyPrinting().create();
        var inputs = new JsonArray();var targets = new JsonArray();
        for (String g : GRADES) {
            var input = V3TestInputs.crude(catalog, g);
            var row = new JsonObject();row.addProperty("id", g);row.add("input", new Gson().toJsonTree(input));inputs.add(row);
            var drop = input.withColumnDiameter(0.0);
            var outcome = V3TestInputs.calculate(drop);
            var target = new JsonObject();target.addProperty("preset", g);target.addProperty("package", drop.packageId());
            var volumes = new JsonObject();
            if (outcome instanceof V3ColumnOutcome.Success success) {
                var p = catalog.requirePackage(drop.packageId());
                for (var s : success.result().streams()) {
                    if (!s.streamId().startsWith("side_liquid_tray_")) continue;
                    double v = 0;
                    for (var f : s.moleFractions()) { int i = p.components().indexOf(f.componentId());
                        if (i >= 0) v += s.molarFlowMolPerSecond() * f.moleFraction() * p.properties().get(i).molecularWeight() / p.properties().get(i).density(); }
                    volumes.addProperty(s.streamId(), v);
                }
            } else volumes.addProperty("failure", outcome.toString());
            target.add("targets_m3_per_second", volumes);targets.add(target);
            System.out.println("o3 capture " + g + " " + volumes);
        }
        var dir = Path.of("tmp/open-thermo-o3/capture");Files.createDirectories(dir);
        Files.writeString(dir.resolve("column-preset-inputs.json"), gson.toJson(inputs));
        Files.writeString(dir.resolve("draw-volume-targets.json"), gson.toJson(targets));
    }
}

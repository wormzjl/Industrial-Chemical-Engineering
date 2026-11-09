package com.wormzjl.icheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Throwaway O3 probe (batch 2026-11-04-open-thermo-data): solves each candidate grade column preset from
 * tmp/open-thermo-o3/presets/ on the classical initializer and reports counters, products, stage temperatures, and the
 * liquid on each draw tray. Copy to src/test/java/com/wormzjl/icheme/science/column/v3/, run
 * ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.O3GradePresetProbe, and move it back.
 * -Do3.presets=a,b limits the presets; -Do3.out names the output file.
 */
class O3GradePresetProbe {
    private static JsonObject run(String id, MaterialCatalog catalog) {
        var input = catalog.presets().column(id).input(catalog);
        var polls = new java.util.concurrent.atomic.AtomicLong();
        var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        var o = new JsonObject();
        o.addProperty("preset", id);
        double feed = 0; for (double f : input.feedComponentMolarFlowsMolPerSecond()) feed += f;
        o.addProperty("feedMolPerSecond", feed);
        o.addProperty("success", outcome instanceof V3ColumnOutcome.Success);
        var d = outcome.diagnostics();
        o.addProperty("initializerIterations", d.initializerIterations());
        o.addProperty("newtonIterations", d.newtonIterations());
        o.addProperty("residualEvaluations", d.residualEvaluations());
        o.addProperty("linearSolves", d.linearSolves());
        o.addProperty("controlPolls", polls.get());
        o.addProperty("solvePath", d.solvePath());
        var events = new JsonArray(); d.events().forEach(events::add); o.add("events", events);
        if (outcome instanceof V3ColumnOutcome.Success success) {
            var streams = new JsonArray();
            for (var s : success.result().streams()) {
                var row = new JsonObject();
                row.addProperty("stream", s.streamId());
                row.addProperty("molPerSecond", s.molarFlowMolPerSecond());
                row.addProperty("kgPerSecond", s.massFlowKgPerSecond());
                row.addProperty("temperatureKelvin", s.temperatureKelvin());
                streams.add(row);
            }
            o.add("streams", streams);
            var nodes = new JsonArray();
            success.result().inspection().ifPresent(i -> i.nodes().forEach(n -> {
                var row = new JsonArray(); row.add(n.temperatureKelvin()); row.add(n.liquidMolPerSecond()); row.add(n.vaporMolPerSecond()); row.add(n.freeWaterMolPerSecond()); nodes.add(row);
            }));
            o.add("nodes_T_L_V_freeWater", nodes);
            var draws = new JsonArray();
            for (var draw : input.sideDraws()) {
                var row = new JsonObject();
                row.addProperty("tray", draw.trayNumber());
                row.addProperty("drawMolPerSecond", draw.molarFlowMolPerSecond());
                success.result().inspection().ifPresent(i -> {
                    var n = i.nodes().get(draw.trayNumber());
                    row.addProperty("trayLiquidMolPerSecond", n.liquidMolPerSecond());
                    row.addProperty("drawOverDrawPlusLiquid", draw.molarFlowMolPerSecond() / (draw.molarFlowMolPerSecond() + n.liquidMolPerSecond()));
                });
                draws.add(row);
            }
            o.add("draws", draws);
        } else {
            o.addProperty("failure", outcome.toString());
        }
        System.out.println("o3 preset " + id + ": success=" + o.get("success") + " polls=" + polls.get() + " path=" + d.solvePath());
        return o;
    }

    @Test
    void solveTheGradePresets() throws Exception {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        try (var files = Files.list(Path.of("tmp/crude-grades/out/assays"))) {
            for (var f : files.toList()) {
                String key = "data/icheme/materials/assays/" + f.getFileName();
                if (!resources.containsKey(key)) resources.put(key, Files.readString(f));
            }
        }
        var ids = new java.util.ArrayList<String>();
        try (var files = Files.list(Path.of("tmp/open-thermo-o3/presets"))) {
            for (var f : files.sorted().toList()) {
                resources.put("data/icheme/materials/presets/" + f.getFileName(), Files.readString(f));
                ids.add(f.getFileName().toString().replace("column_", "").replace(".json", ""));
            }
        }
        String only = System.getProperty("o3.presets", "");
        if (!only.isEmpty()) ids.retainAll(List.of(only.split(",")));
        var catalog = MaterialCatalog.parse(resources);
        var out = new JsonArray();
        if (catalog.presets().columns().containsKey("tia_juana") && Boolean.parseBoolean(System.getProperty("o3.baseline", "true"))) out.add(run("tia_juana", catalog));
        for (String id : ids) out.add(run(id, catalog));
        var file = Path.of(System.getProperty("o3.out", "tmp/open-thermo-o3/grade-presets.json"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(out));
    }
}

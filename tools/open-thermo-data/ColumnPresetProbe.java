package com.wormzjl.icheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O2, measurement 3): the default column preset ({@code tia_juana})
 * on the data before O2 (the material records of the batch's parent commit 03e7b6138, read with git) and after it, each
 * from the learned start the game uses ({@code ColumnUnit.START}, bound by the bundled registry) and from the classical
 * initializer ({@link V3InitializationOptions#CURRENT}). Writes the counters, the products and the stage temperatures to
 * {@code tmp/open-thermo-nist/column-preset.json}. Never committed under {@code src/test}: copy it to
 * {@code src/test/java/com/wormzjl/icheme/science/column/v3/}, run
 * {@code ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.ColumnPresetProbe}, and move it back.
 */
class ColumnPresetProbe {
    private static final String PARENT = "03e7b6138";
    private static final List<String> CHANGED = List.of("properties/nitrogen.json", "properties/tjl20_methane.json", "water/water.json",
            "transport/liquid_calibration.json", "spine/nitrogen.json", "spine/methane.json", "spine/ethane.json", "spine/carbon_dioxide.json",
            "packages/refinery.json", "packages/tjl20.json", "packages/tjl20_nitrogen.json", "packages/pilot_cryogenic.json");
    private static final V3InitializationOptions START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);

    private static String parent(String path) throws Exception {
        var process = new ProcessBuilder("git", "show", PARENT + ":src/main/resources/data/icheme/materials/" + path).redirectErrorStream(false).start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("git show " + path);
        return text;
    }

    private static JsonObject run(String label, MaterialCatalog catalog, boolean learned) {
        var input = catalog.presets().column("tia_juana").input(catalog);
        var model = V3NeuralRegistry.bundled().bind(catalog, input);
        var options = learned ? START : V3InitializationOptions.CURRENT;
        // Work proxy: the solver polls its control at every bounded step (Newton iterations, flashes, inference).
        var polls = new java.util.concurrent.atomic.AtomicLong();
        var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0, options, model,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        var o = new JsonObject();
        o.addProperty("case", label);
        o.addProperty("model", model.modelId());
        o.addProperty("success", outcome instanceof V3ColumnOutcome.Success);
        var d = outcome.diagnostics();
        o.addProperty("initializerIterations", d.initializerIterations());
        o.addProperty("newtonIterations", d.newtonIterations());
        o.addProperty("residualEvaluations", d.residualEvaluations());
        o.addProperty("linearSolves", d.linearSolves());
        o.addProperty("controlPolls", polls.get());
        o.addProperty("solvePath", d.solvePath());
        var events = new JsonArray();
        d.events().forEach(events::add);
        o.add("events", events);
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
            var temperatures = new JsonArray();
            success.result().inspection().ifPresent(i -> i.nodes().forEach(n -> temperatures.add(n.temperatureKelvin())));
            o.add("stageTemperatures", temperatures);
        } else {
            o.addProperty("failure", outcome.toString());
        }
        System.out.println("column preset " + label + ": " + o);
        return o;
    }

    @Test
    void solveTheDefaultPresetBeforeAndAfter() throws Exception {
        var current = MaterialCatalog.bundled();
        var resources = new LinkedHashMap<>(current.resources());
        for (String path : CHANGED) {
            String key = "data/icheme/materials/" + path;
            if (!resources.containsKey(key)) throw new IllegalStateException("no resource " + key);
            resources.put(key, parent(path));
        }
        var before = MaterialCatalog.parse(resources);
        var out = new JsonArray();
        out.add(run("before/learned", before, true));
        out.add(run("before/classical", before, false));
        out.add(run("after/learned", current, true));
        out.add(run("after/classical", current, false));
        var file = Path.of("tmp/open-thermo-nist/column-preset.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }
}

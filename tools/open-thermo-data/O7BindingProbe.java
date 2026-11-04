package com.wormzjl.icheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O7, step 2): with the trained physics compared by value (sidecar
 * schema 4), (1) each compound's and the water's largest deviation from the trained values over the trained range, on the
 * default package; (2) whether the bundled model binds the default column preset ({@code tia_juana}); (3) that preset from
 * the learned start the game uses and from the classical initializer, counted by control polls as O2 counted them
 * ({@code ColumnPresetProbe}). Writes {@code tmp/open-thermo-o7/binding.json}. Never committed under {@code src/test}: copy
 * it to {@code src/test/java/com/wormzjl/icheme/science/column/v3/}, run
 * {@code ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.O7BindingProbe}, and move it back.
 */
class O7BindingProbe {
    private static final V3InitializationOptions START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, 2_000, V3InitializationOptions.Correction.PROGRESS);

    private static JsonObject run(String label, MaterialCatalog catalog, boolean learned) {
        var input = catalog.presets().column("tia_juana").input(catalog);
        var model = V3NeuralRegistry.bundled().bind(catalog, input);
        var polls = new java.util.concurrent.atomic.AtomicLong();
        long start = System.nanoTime();
        var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                learned ? START : V3InitializationOptions.CURRENT, model, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        var o = new JsonObject();
        o.addProperty("case", label);
        o.addProperty("model", model.modelId());
        o.addProperty("wallMs", (System.nanoTime() - start) / 1e6);
        o.addProperty("success", outcome instanceof V3ColumnOutcome.Success);
        o.addProperty("controlPolls", polls.get());
        var d = outcome.diagnostics();
        o.addProperty("solvePath", d.solvePath());
        var events = new JsonArray();
        d.events().stream().limit(3).forEach(events::add);
        o.add("events", events);
        if (outcome instanceof V3ColumnOutcome.Success success) {
            var streams = new JsonObject();
            for (var s : success.result().streams()) streams.addProperty(s.streamId(), s.molarFlowMolPerSecond());
            o.add("streams", streams);
        }
        System.out.println("O7 column " + label + ": " + o);
        return o;
    }

    @Test
    void measure() throws Exception {
        var catalog = MaterialCatalog.bundled();
        byte[] payload, sidecar;
        try (var p = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.payload.json");
             var s = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.sidecar.json")) {
            payload = p.readAllBytes(); sidecar = s.readAllBytes();
        }
        var physics = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload), new ByteArrayInputStream(sidecar)).initializer().physics();
        var out = new JsonObject();
        var pack = catalog.requirePackage("icheme:tjl20_methane");
        var table = new TreeMap<String, JsonObject>();
        for (var d : physics.compare(pack, pack.components())) {
            if (d.subject().contains(" / ")) {
                var pairs = table.computeIfAbsent("pairs", k -> new JsonObject());
                pairs.addProperty("maxAbsolute", Math.max(pairs.has("maxAbsolute") ? pairs.get("maxAbsolute").getAsDouble() : 0, d.value()));
                continue;
            }
            var row = table.computeIfAbsent(d.subject(), k -> new JsonObject());
            row.addProperty(d.quantity(), d.value());
            row.addProperty("max", Math.max(row.has("max") ? row.get("max").getAsDouble() : 0, d.value()));
        }
        var distances = new JsonObject();
        table.forEach(distances::add);
        out.add("distances", distances);
        out.addProperty("range", physics.minimumTemperature() + ".." + physics.maximumTemperature() + " K");
        var input = catalog.presets().column("tia_juana").input(catalog);
        out.addProperty("presetBinds", V3NeuralRegistry.bundled().bind(catalog, input).modelId());
        var runs = new JsonArray();
        runs.add(run("learned", catalog, true));
        runs.add(run("classical", catalog, false));
        runs.add(run("learned-2", catalog, true));
        out.add("runs", runs);
        var file = Path.of("tmp/open-thermo-o7/binding.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out));
    }
}

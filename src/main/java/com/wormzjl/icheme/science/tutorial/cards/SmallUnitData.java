package com.wormzjl.icheme.science.tutorial.cards;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The recorded runs of chapter 4 (batch 2026-11-12-tutorial-system, P4b), {@code small_unit.json}: the bundled "Small crude
 * unit" run on the simulator's path as bundled and with its heater's outlet lowered, written offline by
 * {@code tools/tutorial-recordings/SmallUnit}, held to the simulator by {@code SmallUnitRecordingTest}. Each run: the
 * heater's outlet (K), the crude's hydrocarbon (kg/h), the three products' (overhead, middle, bottoms; kg/h) and shares of
 * the crude by mass, each unit's duty (W). Read once; no Minecraft type.
 */
public final class SmallUnitData {
    public static final String PATH = "/assets/icheme/tutorial/recordings/small_unit.json";
    /** The products, in the file's order. */
    public static final List<String> PRODUCTS = List.of("overhead", "middle", "bottoms");

    public record Run(double heaterKelvin, String status, double crudeKgH, Map<String, Double> productKgH, Map<String, Double> shares, Map<String, Double> duties) {
        public double share(String product) {
            return shares.get(product);
        }

        public double duty(String unit) {
            return duties.get(unit);
        }
    }

    private static List<Run> loaded;

    private SmallUnitData() {
    }

    /** The runs: as bundled first, then with the heater lowered. */
    public static synchronized List<Run> load() {
        if (loaded != null) return loaded;
        try (var in = SmallUnitData.class.getResourceAsStream(PATH)) {
            var root = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in, PATH), StandardCharsets.UTF_8)).getAsJsonObject();
            var runs = new ArrayList<Run>();
            for (var e : root.getAsJsonArray("runs")) {
                var o = e.getAsJsonObject();
                runs.add(new Run(o.get("heater_kelvin").getAsDouble(), o.get("status").getAsString(), o.get("crude_kg_h").getAsDouble(), map(o.getAsJsonObject("product_kg_h")),
                        map(o.getAsJsonObject("shares")), map(o.getAsJsonObject("duties_w"))));
            }
            loaded = List.copyOf(runs);
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Run bundled() {
        return load().getFirst();
    }

    public static Run lowered() {
        return load().get(1);
    }

    private static Map<String, Double> map(com.google.gson.JsonObject o) {
        var out = new LinkedHashMap<String, Double>();
        for (var e : o.entrySet()) out.put(e.getKey(), e.getValue().getAsDouble());
        return java.util.Collections.unmodifiableMap(out);
    }
}

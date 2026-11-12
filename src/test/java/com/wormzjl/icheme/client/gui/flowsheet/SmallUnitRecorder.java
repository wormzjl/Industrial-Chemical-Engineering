package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRuns;
import com.wormzjl.icheme.science.flowsheet.Params;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recorded runs of chapter 4 (batch 2026-11-12-tutorial-system, P4b): the bundled "Small crude unit" run on the
 * simulator's own path ({@link FlowsheetRuns#run}, as {@code SmallCrudeUnitTest} and {@code tools/tutorial-column} run it),
 * as bundled (H-101's outlet at 390 °C) and again with H-101's outlet lowered to {@link #SECOND_KELVIN}. For each run: its
 * status, the crude's and the three products' mass flows of hydrocarbon (overhead: the off-gas and the naphtha; the middle
 * draw; the bottoms), their shares of the crude, and each unit's duty. Written by {@code tools/tutorial-recordings/SmallUnit},
 * held to the simulator by {@code SmallUnitRecordingTest} (holdGates: a cold run of the sheet costs about 223,000 polls).
 */
public final class SmallUnitRecorder {
    public static final String EXAMPLE = "small_crude_unit", HEATER = "H-101";
    /** The second run's H-101 outlet, K. */
    public static final double SECOND_KELVIN = Double.parseDouble(System.getProperty("icheme.tutorial.second", "643.15"));
    /** The products, their streams (by id) summed. */
    public static final Map<String, List<String>> PRODUCTS = products();

    private static Map<String, List<String>> products() {
        var out = new LinkedHashMap<String, List<String>>();
        out.put("overhead", List.of("OFFGAS", "NAPHTHA"));
        out.put("middle", List.of("MIDDLE"));
        out.put("bottoms", List.of("RESIDUE"));
        return java.util.Collections.unmodifiableMap(out);
    }

    private SmallUnitRecorder() {
    }

    /** One run: H-101's outlet (K), the status, the crude's hydrocarbon (kg/h), each product's (kg/h) and share of the crude, each unit's duty (W). */
    public record Run(double heaterKelvin, FlowsheetRun.Status status, double crudeKgH, Map<String, Double> productKgH, Map<String, Double> shares, Map<String, Double> duties) {
    }

    public static Flowsheet bundled() {
        return SheetFixture.example(EXAMPLE);
    }

    /** The bundled sheet with H-101's outlet at {@code kelvin}. */
    public static Flowsheet withHeater(Flowsheet sheet, double kelvin) {
        var units = new ArrayList<Flowsheet.Unit>();
        for (var u : sheet.units()) {
            if (!u.id().equals(HEATER)) {
                units.add(u);
                continue;
            }
            var json = u.params().json().deepCopy();
            json.addProperty("temperature", kelvin);
            units.add(new Flowsheet.Unit(u.id(), u.type(), u.name(), u.x(), u.y(), new Params(json), u.unknown()));
        }
        return new Flowsheet(sheet.basis(), units, sheet.streams(), sheet.settings(), sheet.sizing(), sheet.unknown());
    }

    public static Run run(Flowsheet sheet) {
        double kelvin = sheet.units().stream().filter(u -> u.id().equals(HEATER)).findFirst().orElseThrow().params().number("temperature");
        var run = FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        var thermo = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
        double crude = Double.NaN;
        var kg = new LinkedHashMap<String, Double>();
        var shares = new LinkedHashMap<String, Double>();
        var duties = new java.util.TreeMap<String, Double>();
        if (run.status() == FlowsheetRun.Status.STEADY) {
            crude = 3600 * hydrocarbon(run.streams().get("CRUDE"), thermo);
            for (var e : PRODUCTS.entrySet()) {
                double sum = 0;
                for (var id : e.getValue()) sum += 3600 * hydrocarbon(run.streams().get(id), thermo);
                kg.put(e.getKey(), sum);
                shares.put(e.getKey(), sum / crude);
            }
            // Each unit's duty: its last step's, over the run's passes.
            for (var pass : run.passes())
                for (var step : pass.steps())
                    if (!Double.isNaN(step.duty())) duties.put(step.unit(), step.duty());
        }
        return new Run(kelvin, run.status(), crude, kg, shares, duties);
    }

    /** The two runs: as bundled, and with H-101's outlet at {@link #SECOND_KELVIN}. */
    public static List<Run> record() {
        var sheet = bundled();
        return List.of(run(sheet), run(withHeater(sheet, SECOND_KELVIN)));
    }

    /** A stream's mass of everything but water, kg/s. */
    static double hydrocarbon(FlowStream stream, FlowThermo thermo) {
        double[] a = stream.amountsView();
        double mass = 0;
        for (int i = 0; i < a.length; i++) if (i != thermo.water()) mass += a[i] * SheetFixture.FLUIDS.model().molecularWeight(i);
        return mass;
    }

    public static String toJson(List<Run> runs) {
        var root = new JsonObject();
        root.addProperty("example", EXAMPLE);
        root.addProperty("heater", HEATER);
        var products = new JsonArray();
        PRODUCTS.keySet().forEach(products::add);
        root.add("products", products);
        var all = new JsonArray();
        for (var r : runs) {
            var o = new JsonObject();
            o.addProperty("heater_kelvin", r.heaterKelvin());
            o.addProperty("status", r.status().name());
            o.addProperty("crude_kg_h", r.crudeKgH());
            var kg = new JsonObject();
            r.productKgH().forEach(kg::addProperty);
            o.add("product_kg_h", kg);
            var sh = new JsonObject();
            r.shares().forEach(sh::addProperty);
            o.add("shares", sh);
            var du = new JsonObject();
            r.duties().forEach(du::addProperty);
            o.add("duties_w", du);
            all.add(o);
        }
        root.add("runs", all);
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(root) + "\n";
    }
}

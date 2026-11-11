package com.wormzjl.icheme.science.thermo.phase;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;

import java.util.ArrayList;
import java.util.Locale;
import java.util.TreeMap;

/** Scratch probe: the size of the refinery list's derived E-PPR78 pairs, largest |k_ij| over 250 to 600 K. */
public class PairSizes {
    public static void main(String[] args) {
        var bundled = MaterialCatalog.bundled();
        var refinery = bundled.requirePackage("icheme:refinery");
        var resources = PilotAqueousTestCatalog.resources();
        var pkg = new JsonObject();
        pkg.addProperty("schema_version", 1);
        pkg.addProperty("id", "icheme:timing_island");
        pkg.addProperty("revision", "timing-island-r1");
        pkg.addProperty("method", "icheme:pilot_aqueous_pr78");
        var components = new JsonArray();
        for (var property : refinery.properties()) {
            var c = new JsonObject();
            c.addProperty("id", property.component());
            c.addProperty("properties", property.id());
            components.add(c);
        }
        pkg.add("components", components);
        var evidence = new JsonArray();
        evidence.add("probe only");
        pkg.add("advisory_evidence", evidence);
        resources.put("data/icheme/materials/packages/timing_island.json", pkg.toString());
        var catalog = MaterialCatalog.parse(resources);
        var result = DerivedPairs.derive(catalog, "icheme:timing_island");
        var names = result.components();
        int n = names.size();
        var bySource = new TreeMap<String, Integer>();
        for (var pair : result.pairs()) bySource.merge(pair.source().name(), 1, Integer::sum);
        System.out.println(n + " compounds, " + n * (n - 1) / 2 + " pairs, by source " + bySource);
        double[] tc = result.criticalTemperatures(), pc = result.criticalPressures(), omega = result.acentricFactors();
        var model = result.excess().interactions();
        int pairs = model.pairCount();
        double[] largest = new double[pairs], at298 = new double[pairs];
        double[][] table = new double[pairs][5];
        int column = 0;
        double[] temperatures = {150.0, 250.0, 298.15, 450.0, 700.0};
        double r = 8.31446261815324;
        for (double t : temperatures) {
            double[] s = new double[n], sDt = new double[n], sDt2 = new double[n], b = new double[n];
            for (int i = 0; i < n; i++) {
                double w = omega[i];
                double m = w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
                double root = Math.sqrt(0.45724 * r * r * tc[i] * tc[i] / pc[i]);
                s[i] = root * (1.0 + m * (1.0 - Math.sqrt(t / tc[i])));
                sDt[i] = -root * m / (2.0 * Math.sqrt(t * tc[i]));
                sDt2[i] = root * m / (4.0 * t * Math.sqrt(t * tc[i]));
                b[i] = 0.0778 * r * tc[i] / pc[i];
            }
            double[] value = new double[pairs], d1 = new double[pairs], d2 = new double[pairs];
            model.evaluate(t, s, sDt, sDt2, b, value, d1, d2);
            for (int p = 0; p < pairs; p++) {
                largest[p] = Math.max(largest[p], Math.abs(value[p]));
                table[p][column] = value[p];
                if (t == 298.15) at298[p] = value[p];
            }
            column++;
        }
        for (int c = 0; c < 5; c++) { int big = 0, huge = 0; for (int p = 0; p < pairs; p++) { if (Math.abs(table[p][c]) > 0.3) big++; if (Math.abs(table[p][c]) > 1.0) huge++; } System.out.printf(Locale.ROOT, "at %.2f K: |k| > 0.3 in %d pairs, > 1 in %d%n", temperatures[c], big, huge); }
        double[] thresholds = {0.002, 0.005, 0.01, 0.02, 0.05};
        System.out.println(pairs + " model (E-PPR78) pairs; kept at a threshold on the largest |k| over 250 to 600 K:");
        for (double threshold : thresholds) {
            int kept = 0;
            for (double k : largest) if (k >= threshold) kept++;
            System.out.printf(Locale.ROOT, "  |k| >= %.3f: %d of %d%n", threshold, kept, pairs);
        }
        for (int size : new int[]{12, 24}) {
            var in = new boolean[n];
            for (int i = 0; i < size; i++) in[(int) Math.round(i * (n - 1.0) / (size - 1.0))] = true;
            StringBuilder line = new StringBuilder("  sublist " + size + ": model pairs kept");
            for (double threshold : thresholds) {
                int kept = 0;
                for (int p = 0; p < pairs; p++) if (in[model.first(p)] && in[model.second(p)] && largest[p] >= threshold) kept++;
                line.append(String.format(Locale.ROOT, "  %.3f: %d", threshold, kept));
            }
            int total = 0;
            for (int p = 0; p < pairs; p++) if (in[model.first(p)] && in[model.second(p)]) total++;
            System.out.println(line + "  (all " + total + ")");
        }
        var order = new ArrayList<Integer>();
        for (int p = 0; p < pairs; p++) order.add(p);
        order.sort((x, y) -> Double.compare(largest[y], largest[x]));
        System.out.println("largest:");
        for (int q = 0; q < 14; q++) { int p = order.get(q); System.out.printf(Locale.ROOT, "  %s / %s: %.4g %.4g %.4g %.4g %.4g%n", names.get(model.first(p)), names.get(model.second(p)), table[p][0], table[p][1], table[p][2], table[p][3], table[p][4]); }
        System.out.println("around the median:");
        for (int q = pairs / 2 - 2; q < pairs / 2 + 2; q++) { int p = order.get(q); System.out.printf(Locale.ROOT, "  %s / %s: %.4f at 298 K, largest %.4f%n", names.get(model.first(p)), names.get(model.second(p)), at298[p], largest[p]); }
    }
}

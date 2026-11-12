package com.wormzjl.icheme.science.thermo.phase;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wormzjl.icheme.science.material.DerivedPairs;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scratch timing probe (not a test): time per kernel evaluation on an island-size sublist of the refinery list.
 * Usage: IslandTime today|dry|waterfree|wet N [warmSeconds measureSeconds]. today: the refinery's constant pairs on the
 * sublist (the network's kernel now); waterfree: the derived kernel on the sublist and water, a phase without water;
 * wet: the same kernel, a phase mostly water; dry: the derived kernel on the sublist alone (no water in the list). The sublist is N compounds evenly spaced over the refinery's order.
 */
public class IslandTime {
    public static void main(String[] args) {
        String mode = args[0];
        int size = Integer.parseInt(args[1]);
        double warm = args.length > 2 ? Double.parseDouble(args[2]) : 60.0;
        double measure = args.length > 3 ? Double.parseDouble(args[3]) : 60.0;
        var bundled = MaterialCatalog.bundled();
        var refinery = bundled.requirePackage("icheme:refinery");
        int all = refinery.components().size();
        int[] pick = new int[size];
        for (int i = 0; i < size; i++) pick[i] = (int) Math.round(i * (all - 1.0) / (size - 1.0));
        PengRobinsonKernel kernel;
        int n, water = -1, libraryPairs = 0;
        for (int i = 0; i < size; i++) for (int j = i + 1; j < size; j++)
            if (refinery.interactions().get(pick[i]).get(pick[j]) != 0.0) libraryPairs++;
        if (mode.equals("today")) {
            double[] tc = new double[size], pc = new double[size], omega = new double[size];
            double[][] k = new double[size][size];
            for (int i = 0; i < size; i++) {
                var pr = refinery.properties().get(pick[i]).pr();
                tc[i] = pr.criticalTemperature(); pc[i] = pr.criticalPressure(); omega[i] = pr.acentricFactor();
                for (int j = 0; j < size; j++) k[i][j] = refinery.interactions().get(pick[i]).get(pick[j]);
            }
            kernel = new PengRobinsonKernel(tc, pc, omega, k, Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
            n = size;
        } else {
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
            var w = new JsonObject();
            w.addProperty("id", "Water");
            w.addProperty("properties", "icheme:cubic_water");
            components.add(w);
            pkg.add("components", components);
            var evidence = new JsonArray();
            evidence.add("timing probe only");
            pkg.add("advisory_evidence", evidence);
            resources.put("data/icheme/materials/packages/timing_island.json", pkg.toString());
            var catalog = MaterialCatalog.parse(resources);
            var ids = new ArrayList<String>();
            for (int i : pick) ids.add(refinery.components().get(i));
            if (!mode.equals("dry")) ids.add("Water");
            var result = DerivedPairs.derive(catalog, "icheme:timing_island", ids);
            kernel = result.kernel(1.0, 3000.0, 1.0, 1.0e9);
            n = kernel.componentCount();
            List<String> names = result.components();
            water = names.indexOf("Water");
        }
        double[][] compositions = new double[5][n];
        for (int c = 0; c < 5; c++) for (int i = 0; i < n; i++) {
            double share = 1.0 + ((i * 7 + c * 3) % 11);
            if (i == water) share = mode.equals("wet") ? 40.0 * n : 0.0;
            compositions[c][i] = share;
        }
        var workspace = kernel.newWorkspace();
        var output = kernel.newEvaluation();
        double t = 330.0, p = 2.0e5, sink = 0.0;
        long calls = 0;
        long end = System.nanoTime() + (long) (warm * 1e9);
        while (System.nanoTime() < end) for (int r = 0; r < 1000; r++) {
            kernel.evaluate(t, p, compositions[r % 5], (r & 1) == 0 ? PengRobinsonKernel.Root.LIQUID : PengRobinsonKernel.Root.VAPOR, workspace, output);
            sink += output.compressibility();
        }
        long start = System.nanoTime(), now;
        end = start + (long) (measure * 1e9);
        do {
            for (int r = 0; r < 1000; r++) {
                kernel.evaluate(t, p, compositions[r % 5], (r & 1) == 0 ? PengRobinsonKernel.Root.LIQUID : PengRobinsonKernel.Root.VAPOR, workspace, output);
                sink += output.compressibility();
            }
            calls += 1000;
            now = System.nanoTime();
        } while (now < end);
        System.out.printf(Locale.ROOT, "%s %d: %d compounds in the kernel, %d of %d sublist pairs in the library, %.1f ns per evaluation (%d calls, sink %.3e)%n",
                mode, size, n, libraryPairs, size * (size - 1) / 2, (now - start) / (double) calls, calls, sink);
    }
}

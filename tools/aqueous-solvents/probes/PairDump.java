package com.wormzjl.icheme.science.material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * W6 parallel fits, gate G-a (batch 2026-11-11-aqueous-solvents, W6 plan sections 13 and 15): the whole wet list of the
 * network package derived on a fresh catalog, every pair (source, description), the missing pairs and notices, the
 * identity, the fit count and every excess parameter of every ordered pair, doubles as {@link Double#toHexString}. Run it
 * before and after a change to {@link DerivedPairs} and diff the two files: an empty diff is no moved number.
 * Usage: {@code PairDump <output file>}; package {@code com.wormzjl.icheme.science.material}, class path
 * {@code build/classes/java/main}, {@code build/resources/main}, gson and the ejml jars.
 */
public final class PairDump {
    public static void main(String[] args) throws IOException {
        var parsed = MaterialCatalog.parse(new HashMap<>(MaterialCatalog.bundled().resources()));
        String network = parsed.presets().network().packageId();
        var catalog = parsed.withWater(network);
        var ids = catalog.requirePackage(network).components();
        var r = DerivedPairs.derive(catalog, network, ids);
        Files.write(Path.of(args[0]), dump(r));
        System.out.println(ids.size() + " compounds, " + r.pairs().size() + " pairs, fits " + r.fitsRun() + " -> " + args[0]);
    }

    static List<String> dump(DerivedPairs.Result r) {
        var out = new ArrayList<String>();
        out.add("components " + r.components());
        out.add("identity " + r.identity());
        out.add("fits " + r.fitsRun());
        for (var p : r.pairs()) out.add("pair " + p.first() + " / " + p.second() + " " + p.source() + " " + p.detail());
        for (var m : r.missing()) out.add("missing " + m);
        for (var m : r.notices()) out.add("notice " + m);
        int n = r.components().size();
        double[] tc = r.criticalTemperatures(), pc = r.criticalPressures(), omega = r.acentricFactors();
        double[][] alpha = r.mathiasCopeman();
        for (int i = 0; i < n; i++) {
            var line = new StringBuilder("compound " + r.components().get(i) + " " + hex(tc[i]) + " " + hex(pc[i]) + " " + hex(omega[i]) + " polar " + r.polar(i));
            if (alpha[i] != null) for (double a : alpha[i]) line.append(' ').append(hex(a));
            out.add(line.toString());
        }
        var e = r.excess();
        out.add("excess nrtl " + e.nrtlPairCount() + " rankOne " + e.rankOne() + " model " + (e.interactions() != null));
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                out.add("cell " + i + " " + j + " " + e.classical(i, j) + " " + hex(e.alpha(i, j)) + " " + hex(e.interaction(i, j)) + " "
                        + hex(e.interactionSlope(i, j)) + " " + e.interactionPair(i, j) + " " + hex(e.energyIntercept(j, i)) + " "
                        + hex(e.energySlope(j, i)) + " " + hex(e.lineLow(i, j)) + " " + hex(e.lineHigh(i, j)) + " " + hex(e.lineFade(i, j)));
            }
        return out;
    }

    private static String hex(double v) { return Double.toHexString(v); }
}

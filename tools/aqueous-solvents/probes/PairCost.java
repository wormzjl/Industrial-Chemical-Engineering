package com.wormzjl.icheme.science.material;
import java.util.*;

/** W6 G9: the cost of each of ethanol's pairs (and, for scale, water's), each pair derived alone on a fresh wet catalog. */
public class PairCost {
    public static void main(String[] args) {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        var parsed = MaterialCatalog.parse(resources);
        String network = parsed.presets().network().packageId();
        var all = parsed.withWater(network).requirePackage(network).components();
        for (String solvent : List.of("Ethanol", "Water")) {
            double total = 0; int fits = 0; var rows = new ArrayList<String>(); var bySource = new TreeMap<String, double[]>();
            for (String other : all) {
                if (other.equals(solvent) || other.equals("Ethanol") && solvent.equals("Water")) continue;
                var catalog = MaterialCatalog.parse(resources).withWater(network);
                long t0 = System.nanoTime();
                var r = DerivedPairs.derive(catalog, network, List.of(solvent, other));
                double ms = (System.nanoTime() - t0) / 1e6;
                var pair = r.pairs().getFirst();
                total += ms; fits += r.fitsRun();
                bySource.computeIfAbsent(pair.source().name(), k -> new double[3]);
                var b = bySource.get(pair.source().name()); b[0]++; b[1] += ms; b[2] += r.fitsRun();
                rows.add(String.format(Locale.ROOT, "%8.1f ms %d fits %-9s %s", ms, r.fitsRun(), pair.source(), other));
            }
            rows.sort(Comparator.reverseOrder());
            System.out.printf(Locale.ROOT, "%s: %d pairs alone, %.0f ms, %d fits%n", solvent, all.size() - 1, total, fits);
            bySource.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-9s %3.0f pairs %7.1f ms %3.0f fits%n", k, v[0], v[1], v[2]));
            rows.stream().limit(8).forEach(l -> System.out.println("  " + l)); rows.stream().filter(l -> l.contains("MISSING") || l.contains("HOMOMORPH")).forEach(l -> System.out.println("  " + l));
        }
    }
}

package com.wormzjl.icheme.science.material;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.*;

/** W6 G9 split: the whole list's pair derivation with and without ethanol on fresh catalogs, then the model build on warm pairs. */
public class LoadSplit {
    public static void main(String[] args) {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        for (int round = 0; round < 3; round++) {
            for (boolean ethanol : new boolean[]{false, true}) {
                var parsed = MaterialCatalog.parse(resources);
                String network = parsed.presets().network().packageId();
                var catalog = parsed.withWater(network);
                var ids = new ArrayList<>(catalog.requirePackage(network).components());
                if (!ethanol) ids.remove("Ethanol");
                long t0 = System.nanoTime();
                var r = DerivedPairs.derive(catalog, network, ids);
                long t1 = System.nanoTime();
                var counts = new TreeMap<String, Integer>();
                for (var p : r.pairs()) counts.merge(p.source().name(), 1, Integer::sum);
                int withEthanol = 0;
                for (var p : r.pairs()) if (p.source() == DerivedPairs.Source.ACTIVITY && (p.first().equals("Ethanol") || p.second().equals("Ethanol"))) withEthanol++;
                String line = String.format(Locale.ROOT, "round %d %s: %d compounds, derive %.0f ms, fits %d, sources %s, ethanol activity %d", round, ethanol ? "with ethanol" : "without", ids.size(), (t1 - t0) / 1e6, r.fitsRun(), counts, withEthanol);
                if (ethanol) {
                    long t2 = System.nanoTime();
                    MaterialRuntime.with(parsed, network, () -> FluidThermodynamics.forNetwork(parsed, network));
                    line += String.format(Locale.ROOT, ", then the model on warm pairs %.0f ms", (System.nanoTime() - t2) / 1e6);
                }
                System.out.println(line);
            }
        }
    }
}

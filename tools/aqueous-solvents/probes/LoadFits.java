package com.wormzjl.icheme.science.material;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.HashMap;

/** W6 G9: a fresh catalog (empty pair cache), then the network's whole-list model; fits run and wall time. */
public class LoadFits {
    public static void main(String[] args) {
        var resources = new HashMap<>(MaterialCatalog.bundled().resources());
        for (int round = 0; round < 3; round++) {
            long t0 = System.nanoTime();
            var catalog = MaterialCatalog.parse(resources);
            long t1 = System.nanoTime();
            String network = catalog.presets().network().packageId();
            var model = MaterialRuntime.with(catalog, network, () -> FluidThermodynamics.forNetwork(catalog, network));
            long t2 = System.nanoTime();
            System.out.printf("round %d: parse %.0f ms, whole-list model %.0f ms, fits run %d, compounds %d%n", round, (t1 - t0) / 1e6, (t2 - t1) / 1e6,
                    catalog.derivedPairCache().fitsRun(), model.components().size());
        }
    }
}

package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.runtime.fluid.FluidPresetCatalog;
import com.wormzjl.icheme.science.fluid.thermo.*;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import org.junit.jupiter.api.Test;

/**
 * Throwaway O3 probe: each network fluid preset's vapour volume share at 298.15 K and 1 atm and its bubble point at 298.15 K
 * and 350 K (bisection on the pressure for a zero vapour volume, one flashTP each), for the comments the grades re-baseline.
 * Copy to src/test/java/com/wormzjl/icheme/science/fluid/network/, run ./gradlew test --offline --tests '*O3GradeFluidProbe', move back.
 */
class O3GradeFluidProbe {
    @Test void bubblePoints() {
        var catalog = MaterialCatalog.bundled();
        var model = FluidThermodynamics.forNetwork(catalog, FluidMaterialCatalog.networkPackage(catalog));
        for (var preset : FluidPresetCatalog.resolve(catalog)) {
            var n = preset.moleFractions().clone();
            var s = model.flashTP(298.15, 101325, n, () -> {});
            System.out.printf(java.util.Locale.ROOT, "o3 fluid %s: vapour volume share at 298.15 K, 1 atm %.4f; bubble %.1f kPa at 298.15 K, %.1f kPa at 350 K%n",
                    preset.id(), s.vaporVolume() / s.volume(), bubble(model, n, 298.15) / 1000, bubble(model, n, 350) / 1000);
        }
    }
    private static double bubble(FluidThermodynamics model, double[] n, double t) {
        double lo = 1000, hi = 5e6;
        if (model.flashTP(t, hi, n, () -> {}).vaporVolume() > 0) return Double.NaN;
        for (int i = 0; i < 60; i++) { double mid = Math.sqrt(lo * hi); if (model.flashTP(t, mid, n, () -> {}).vaporVolume() > 0) lo = mid; else hi = mid; }
        return hi;
    }
}

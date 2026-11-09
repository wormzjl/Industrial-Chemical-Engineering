package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * PROBE (P2, scratch only): the free-water flashes of LegacyNetworkPathPinTest's digest with their numbers, so the
 * outputs of two trees diff to the points that moved and by how much (LegacyPinPointProbe's flash stage, numbers printed).
 */
class LegacyPinFlashProbe {
    @Test
    void flashes() {
        var catalog = MaterialCatalog.bundled();
        var model = FluidTestSupport.model(catalog, LegacyNetworkPathPinTest.NETWORK);
        var hydrocarbon = model.hydrocarbon;
        int n = hydrocarbon.componentCount();
        int nitrogen = hydrocarbon.components().indexOf("Nitrogen");
        double[] pure = new double[n];
        pure[nitrogen] = 1;
        double[] mixture = new double[n];
        for (int i = 0; i < n; i++) mixture[i] = i == nitrogen ? 0.1 : (i % 5 + 1) * 0.01;
        double[] lean = new double[n];
        lean[nitrogen] = 0.9;
        lean[(nitrogen + 1) % n] = 0.1;
        double[][] compositions = {pure, lean, mixture};
        String[] names = {"pure", "lean", "mixture"};
        StringBuilder out = new StringBuilder();
        for (double t : new double[] {77.0, 110.0, 150.0, 293.15, 350.0, 450.0, 700.0}) {
            for (double p : new double[] {1.0e5, 5.0e5, 2.0e6}) {
                for (int c = 0; c < 3; c++) {
                    double[] overall = new double[n + 1];
                    System.arraycopy(compositions[c], 0, overall, 0, n);
                    overall[n] = 0.1;
                    String key = "FLASH " + t + " " + p + " " + names[c] + ": ";
                    try {
                        var s = model.flashTP(t, p, overall, () -> { });
                        out.append(key).append(String.format(Locale.ROOT, "V %.17g H %.17g U %.17g wl %.17g wv %.17g vel %.17g%n",
                                s.volume(), s.enthalpy(), s.internalEnergy(), s.waterLiquid(), s.waterVapor(), model.velocityLimit(s)));
                    } catch (RuntimeException refused) {
                        out.append(key).append("R ").append(refused).append('\n');
                    }
                }
            }
        }
        System.out.println("PIN FLASH PROBE\n" + out);
    }
}

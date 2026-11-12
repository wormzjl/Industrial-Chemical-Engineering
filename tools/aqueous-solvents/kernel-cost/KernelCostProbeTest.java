package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import org.junit.jupiter.api.Test;

/** Cost probe (tools/aqueous-solvents/kernel-cost/kernel-probe.js copies it into src/test and removes it): never committed there. */
class KernelCostProbeTest {
    @Test void cost() throws Exception {
        var model = FluidTestSupport.networkModel();
        if (System.getProperty("kprobe.dry") != null) {
            var dryNames = new java.util.ArrayList<>(model.components()); dryNames.remove("Water");
            PengRobinsonKernel.PROBE_LARGE = null; model = model.on(dryNames, false);
        }
        var names = model.components();
        model.initialNitrogenCharge(1, 298.15, 101325, () -> {});
        var kernel = PengRobinsonKernel.PROBE_LARGE;
        int n = kernel.componentCount();
        System.out.println("KPROBE excess=" + kernel.hasExcessPairs() + " rankOne=" + kernel.usesRankOneMixing());
        System.out.println("KPROBE components=" + names.size() + " kernel n=" + n + " pairs=" + length(kernel, "pairFirst") + " cells=" + length(kernel, "cellPartner"));
        int water = System.getProperty("kprobe.dry") != null ? names.indexOf("Methane") : names.indexOf("Water"), nitrogen = names.indexOf("Nitrogen");
        double[] liquid = new double[n], gas = new double[n];
        liquid[water] = 0.9999; liquid[nitrogen] = 1e-4;
        gas[water] = 0.03; gas[nitrogen] = 0.97;
        if (System.getProperty("kprobe.dense") != null) {
            java.util.Arrays.fill(liquid, 0.5 / (n - 1)); liquid[water] = 0.5;
            java.util.Arrays.fill(gas, 0.5 / (n - 1)); gas[nitrogen] = 0.5;
            System.out.println("KPROBE dense");
        }
        var workspace = kernel.newWorkspace();
        var output = kernel.newEvaluation();
        var derivatives = kernel.newDerivatives();
        double sink = 0;
        double gasPressure = System.getProperty("kprobe.dense") != null ? 1000 : 130000;
        for (int round = 0; round < 6; round++) {
            int calls = 20000;
            long a = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                kernel.evaluate(298.15 + (i & 7), 130000, liquid, PengRobinsonKernel.Root.LIQUID, workspace, output);
                sink += output.logFugacityCoefficient(water);
                kernel.evaluate(298.15 + (i & 7), gasPressure, gas, PengRobinsonKernel.Root.VAPOR, workspace, output);
                sink += output.logFugacityCoefficient(water);
            }
            long b = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                kernel.evaluateDerivatives(298.15 + (i & 7), 130000, liquid, PengRobinsonKernel.Root.LIQUID, workspace, derivatives);
                sink += derivatives.dLogPhiDtView()[water];
                kernel.evaluateDerivatives(298.15 + (i & 7), gasPressure, gas, PengRobinsonKernel.Root.VAPOR, workspace, derivatives);
                sink += derivatives.dLogPhiDtView()[water];
            }
            long c = System.nanoTime();
            System.out.println("KPROBE round " + round + " evaluate " + (b - a) / (2.0 * calls) + " ns, derivatives " + (c - b) / (2.0 * calls) + " ns");
        }
        System.out.println("KPROBE sink " + Double.toHexString(sink));
    }
    private static int length(Object kernel, String field) throws Exception {
        var f = PengRobinsonKernel.class.getDeclaredField(field); f.setAccessible(true);
        return java.lang.reflect.Array.getLength(f.get(kernel));
    }
}

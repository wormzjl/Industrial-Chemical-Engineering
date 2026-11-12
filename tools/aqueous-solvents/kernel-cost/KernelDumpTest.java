package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Kernel output dump (tools/aqueous-solvents/kernel-cost/kernel-dump.js copies it into src/test and removes it): never
 * committed there. Every output of the kernel on the network list, in hexadecimal, for the gate's five supports
 * (PengRobinsonKernelHuronVidalTest.aCarriedSupportKeepsEveryOutputOnItBitForBit) at a liquid and a vapour root, on a
 * carried and on a whole-list workspace: Z, h^R, a, b, da/dT, dh^R/dT, d2a/dT2 and per compound ln phi, d ln phi/dT, the
 * partial enthalpy and the dn row. Writes tmp/kernel-support/kernel-dump.txt; diff two runs.
 */
class KernelDumpTest {
    @Test void dump() throws Exception {
        var model = FluidTestSupport.networkModel().hydrocarbon;
        PengRobinsonKernel kernel = model.evaluator().kernel();
        List<String> species = model.species();
        int n = kernel.componentCount();
        int water = species.indexOf("Water"), nitrogen = species.indexOf("Nitrogen");
        int ethanol = species.indexOf("Ethanol"), ammonia = species.indexOf("Ammonia");
        int cut = -1;
        for (int i = 0; i < n && cut < 0; i++) if (species.get(i).startsWith("crude_")) cut = i;
        List<int[]> supports = new ArrayList<>();
        supports.add(new int[] {water, nitrogen});
        supports.add(new int[] {water, cut});
        supports.add(new int[] {water, ethanol, ammonia});
        List<Integer> half = new ArrayList<>();
        for (int i = 0; i < n; i += 2) half.add(i);
        if (!half.contains(water)) half.add(water);
        if (!half.contains(ethanol)) half.add(ethanol);
        supports.add(half.stream().mapToInt(Integer::intValue).toArray());
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        supports.add(all);
        double[][] states = {{320.0, 2.0e5}, {420.0, 1.0e5}};
        PengRobinsonKernel.Root[] roots = {PengRobinsonKernel.Root.LIQUID, PengRobinsonKernel.Root.VAPOR};
        var out = new ArrayList<String>();
        out.add("compounds " + n);
        for (int[] support : supports) {
            double[] feed = new double[n];
            for (int at = 0; at < support.length; at++) feed[support[at]] = 1.0 + (support[at] * 37 % 11);
            feed[water] += 3.0 * support.length;
            for (int r = 0; r < roots.length; r++) {
                for (boolean carried : new boolean[] {true, false}) {
                    double t = states[r][0], p = states[r][1];
                    var workspace = kernel.newWorkspace();
                    if (carried) workspace.carry(feed);
                    var d = kernel.newDerivatives();
                    kernel.evaluateDerivatives(t, p, feed, roots[r], workspace, d);
                    double second = kernel.secondTemperatureDerivative(t, workspace, new double[n], new double[n], new double[n]);
                    var e = d.evaluation();
                    out.add("case " + support.length + " " + roots[r] + (carried ? " carried" : " whole") + " Z " + hex(e.compressibility())
                            + " hR " + hex(e.residualEnthalpyJoulesPerMol()) + " a " + hex(e.aMix()) + " b " + hex(e.bMix())
                            + " dadT " + hex(e.daMixDt()) + " dhdT " + hex(d.dResidualEnthalpyDt()) + " d2adT2 " + hex(second));
                    for (int i = 0; i < n; i++) {
                        var row = new StringBuilder("  " + i + " lnphi " + hex(e.logFugacityCoefficient(i)) + " dT " + hex(d.dLogPhiDtView()[i])
                                + " h " + hex(d.partialMolarResidualEnthalpyView()[i]) + " dn");
                        for (double v : d.dLogPhiDnRowView(i)) row.append(' ').append(hex(v));
                        out.add(row.toString());
                    }
                }
            }
        }
        Path file = Path.of("tmp/kernel-support/kernel-dump.txt");
        Files.createDirectories(file.getParent());
        Files.write(file, out);
        System.out.println("KDUMP " + out.size() + " lines -> " + file.toAbsolutePath());
    }

    private static String hex(double value) { return Double.toHexString(value); }
}

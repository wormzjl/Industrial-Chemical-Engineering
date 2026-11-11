package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PengRobinsonKernel.Mixing#HURON_VIDAL} and the Mathias-Copeman alpha: with every pair classical the rule is the
 * quadratic {@link PengRobinsonKernel.Mixing#CLASSICAL} plan to roundoff, in every value and derivative; on polar
 * mixtures with NRTL pairs {@code ln phi_i} matches an oracle written from the rule and the composition derivative of
 * an independent {@code n g^R/(R T)}; every temperature and composition derivative matches central differences of the
 * kernel's own values; Gibbs-Duhem, symmetry and {@code sum x_i h_i = H^R} hold; a compound without coefficients keeps
 * today's arithmetic bit for bit, and water's Mathias-Copeman alpha gives its 100 C vapour pressure.
 */
class PengRobinsonKernelHuronVidalTest {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double LAMBDA = HuronVidalExcess.LAMBDA;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double[] WATER_ALPHA = {0.91976, -0.33844, 0.33431};
    private static final double TEMPERATURE_STEP = 1.0e-3;
    /**
     * Larger than the hydrocarbon tests' 1e-6: a vapour at 1 bar has {@code d ln phi/dn} near 1e-4, where the cubic
     * root's last-digit noise over a 1e-6 step is already 1e-6 of the value; at 1e-4 the curvature of a dilute
     * component in a supersaturated vapour is (the error falls as h^2 from 1e-2 to 1e-4 there).
     */
    private static final double MOLE_STEP = 1.0e-5;
    private static final double DIFFERENCE_TOLERANCE = 1.0e-6;

    // ---------------------------------------------------------------------------------------------------------------
    // Classical pairs: the quadratic rule
    // ---------------------------------------------------------------------------------------------------------------

    /** Methane, ethane, propane, n-decane (the second kappa branch), CO2. */
    private static final double[] HC_TC = {190.56, 305.32, 369.83, 617.7, 304.13};
    private static final double[] HC_PC = {4.599e6, 4.872e6, 4.248e6, 2.11e6, 7.3773e6};
    private static final double[] HC_OMEGA = {0.0115, 0.0995, 0.1523, 0.4923, 0.22394};

    @Test void classicalPairsReproduceTheQuadraticRule() {
        double[][] k = new double[5][5];
        set(k, 0, 4, 0.10);
        set(k, 1, 4, 0.13);
        set(k, 2, 4, 0.135);
        set(k, 3, 4, 0.11);
        set(k, 0, 3, 0.04);
        set(k, 2, 3, -0.01);
        // CH4/C2H6 undeclared, C2H6/C3H8 declared zero: both are k = 0.
        var model = HuronVidalExcess.builder(5)
                .classical(0, 4, 0.10).classical(1, 4, 0.13).classical(4, 2, 0.135).classical(3, 4, 0.11)
                .classical(0, 3, 0.04).classical(2, 3, -0.01).classical(1, 2, 0.0).build();
        int compared = compareWithQuadratic(HC_TC, HC_PC, HC_OMEGA, k, model, new double[][] {
                {0.5, 0.2, 0.1, 0.05, 0.15}, {0.05, 0.1, 0.2, 0.6, 0.05}, {0.2, 0.2, 0.2, 0.2, 0.2}});
        // Three components, all pairs nonzero.
        double[] tc = {HC_TC[0], HC_TC[2], HC_TC[4]};
        double[] pc = {HC_PC[0], HC_PC[2], HC_PC[4]};
        double[] omega = {HC_OMEGA[0], HC_OMEGA[2], HC_OMEGA[4]};
        double[][] k3 = new double[3][3];
        set(k3, 0, 1, 0.02);
        set(k3, 0, 2, 0.10);
        set(k3, 1, 2, 0.135);
        var model3 = HuronVidalExcess.builder(3).classical(0, 1, 0.02).classical(0, 2, 0.10).classical(1, 2, 0.135).build();
        compared += compareWithQuadratic(tc, pc, omega, k3, model3, new double[][] {{0.7, 0.2, 0.1}, {0.1, 0.6, 0.3}});
        // No interaction at all: the quadratic plan's rank-one branch.
        assertTrue(HuronVidalExcess.builder(3).build().rankOne());
        compared += compareWithQuadratic(tc, pc, omega, new double[3][3], HuronVidalExcess.builder(3).build(),
                new double[][] {{0.3, 0.3, 0.4}});
        System.out.println("HURON_VIDAL with classical pairs vs CLASSICAL: " + compared + " values within 1e-11");
        assertTrue(compared > 3000, "too few comparisons: " + compared);
    }

    private static int compareWithQuadratic(double[] tc, double[] pc, double[] omega, double[][] k, HuronVidalExcess model,
                                            double[][] compositions) {
        var quadratic = new PengRobinsonKernel(tc, pc, omega, k, 1.0, 3000.0, 1.0, 1.0e9);
        var rule = PengRobinsonKernel.huronVidal(tc, pc, omega, model, null, 1.0, 3000.0, 1.0, 1.0e9);
        assertEquals(PengRobinsonKernel.Mixing.HURON_VIDAL, rule.mixing());
        int n = tc.length;
        var quadraticWorkspace = quadratic.newWorkspace();
        var ruleWorkspace = rule.newWorkspace();
        var expected = quadratic.newDerivatives();
        var actual = rule.newDerivatives();
        double[][] scratch = new double[6][n];
        int compared = 0;
        double tolerance = 1.0e-11;
        for (double t : new double[] {200.0, 260.0, 320.0, 400.0, 480.0}) {
            for (double p : new double[] {1.0e5, 1.0e6, 4.0e6}) {
                for (double[] x : compositions) {
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        try {
                            quadratic.evaluateDerivatives(t, p, x, root, quadraticWorkspace, expected);
                            rule.evaluateDerivatives(t, p, x, root, ruleWorkspace, actual);
                        } catch (RuntimeException unavailable) { continue; }
                        String at = "T=" + t + " P=" + p + " " + root;
                        var e = expected.evaluation();
                        var f = actual.evaluation();
                        compared += close(e.logFugacityCoefficientsView(), f.logFugacityCoefficientsView(), tolerance, at + " ln phi");
                        compared += close(e.compressibility(), f.compressibility(), 0.0, tolerance, at + " Z");
                        compared += close(e.residualEnthalpyJoulesPerMol(), f.residualEnthalpyJoulesPerMol(),
                                R * t, tolerance, at + " h^R");
                        compared += close(e.aMix(), f.aMix(), 0.0, tolerance, at + " a");
                        compared += close(e.bMix(), f.bMix(), 0.0, tolerance, at + " b");
                        compared += close(e.daMixDt(), f.daMixDt(), 0.0, tolerance, at + " da/dT");
                        compared += close(expected.dLogPhiDtView(), actual.dLogPhiDtView(), tolerance, at + " d ln phi/dT");
                        compared += close(expected.partialMolarResidualEnthalpyView(), actual.partialMolarResidualEnthalpyView(),
                                tolerance, at + " h^R_i");
                        compared += close(expected.dResidualEnthalpyDt(), actual.dResidualEnthalpyDt(), R, tolerance, at + " dh^R/dT");
                        double scale = 0.0;
                        for (int i = 0; i < n; i++) for (double v : expected.dLogPhiDnRowView(i)) scale = Math.max(scale, Math.abs(v));
                        for (int i = 0; i < n; i++) {
                            for (int j = 0; j < n; j++) {
                                compared += close(expected.dLogPhiDnRowView(i)[j], actual.dLogPhiDnRowView(i)[j], scale,
                                        tolerance, at + " d ln phi_" + i + "/dn_" + j);
                            }
                        }
                        double second = quadratic.secondTemperatureDerivative(t, quadraticWorkspace, scratch[0], scratch[1], scratch[2]);
                        double ruleSecond = rule.secondTemperatureDerivative(t, ruleWorkspace, scratch[3], scratch[4], scratch[5]);
                        compared += close(second, ruleSecond, 0.0, tolerance, at + " d2a/dT2");
                    }
                }
            }
        }
        return compared;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Polar mixtures with NRTL pairs
    // ---------------------------------------------------------------------------------------------------------------

    /** Ethanol, water (Mathias-Copeman), benzene, CO2. */
    private static final double[] POLAR_TC = {514.71, 647.096, 562.05, 304.13};
    private static final double[] POLAR_PC = {6.268e6, 22.064e6, 4.895e6, 7.3773e6};
    private static final double[] POLAR_OMEGA = {0.644, 0.3443, 0.2103, 0.22394};
    private static final double[][] POLAR_ALPHA = {null, WATER_ALPHA, null, null};

    /** {@code dg_ij} (i around j) and {@code dg_ji} at two temperatures. */
    private record Nrtl(int i, int j, double alpha, double t1, double gij1, double gji1, double t2, double gij2, double gji2) {}

    private static final List<Nrtl> POLAR_PAIRS = List.of(
            new Nrtl(0, 1, 0.30, 298.15, 1500.0, 5000.0, 373.15, 2500.0, 4200.0),
            new Nrtl(1, 2, 0.20, 298.15, 6000.0, 3000.0, 373.15, 5500.0, 3500.0),
            new Nrtl(0, 2, 0.47, 298.15, 1800.0, 2200.0, 373.15, 1600.0, 2000.0));
    /** CO2-water classical; CO2 with ethanol and benzene undeclared (classical, k = 0). */
    private static final double CO2_WATER_K = 0.1;

    private record Mixture(String name, double[] tc, double[] pc, double[] omega, double[][] alphaCoefficients,
                           List<Nrtl> pairs, double[][] k, List<double[]> compositions) {
        int size() { return tc.length; }
        PengRobinsonKernel kernel() {
            var builder = HuronVidalExcess.builder(size());
            for (Nrtl pair : pairs) {
                builder.nrtl(pair.i, pair.j, pair.alpha, pair.t1, pair.gij1, pair.gji1, pair.t2, pair.gij2, pair.gji2);
            }
            for (int i = 0; i < size(); i++) for (int j = i + 1; j < size(); j++) if (k[i][j] != 0.0) builder.classical(i, j, k[i][j]);
            return PengRobinsonKernel.huronVidal(tc, pc, omega, builder.build(), alphaCoefficients, 1.0, 3000.0, 1.0, 1.0e9);
        }
    }

    private static List<Mixture> polarMixtures() {
        var mixtures = new ArrayList<Mixture>();
        mixtures.add(new Mixture("ethanol-water", slice(POLAR_TC, 2), slice(POLAR_PC, 2), slice(POLAR_OMEGA, 2),
                new double[][] {null, WATER_ALPHA}, List.of(POLAR_PAIRS.get(0)), new double[2][2],
                List.of(new double[] {0.1, 0.9}, new double[] {0.5, 0.5}, new double[] {0.9, 0.1})));
        mixtures.add(new Mixture("ethanol-water-benzene", slice(POLAR_TC, 3), slice(POLAR_PC, 3), slice(POLAR_OMEGA, 3),
                new double[][] {null, WATER_ALPHA, null}, POLAR_PAIRS, new double[3][3],
                List.of(new double[] {0.3, 0.5, 0.2}, new double[] {0.1, 0.1, 0.8}, new double[] {0.2, 0.75, 0.05})));
        double[][] k = new double[4][4];
        set(k, 1, 3, CO2_WATER_K);
        mixtures.add(new Mixture("ethanol-water-benzene-CO2", POLAR_TC, POLAR_PC, POLAR_OMEGA, POLAR_ALPHA, POLAR_PAIRS, k,
                List.of(new double[] {0.3, 0.45, 0.2, 0.05}, new double[] {0.05, 0.1, 0.05, 0.8})));
        return mixtures;
    }

    private static final double[][] POLAR_STATES = {{300.0, 1.0e5}, {340.0, 1.0e5}, {373.15, 1.0e5}, {420.0, 5.0e5},
            {300.0, 2.0e6}, {450.0, 2.0e6}, {520.0, 5.0e6}};

    @Test void nrtlRuleMatchesTheOracleAndTheGibbsEnergyDerivative() {
        int compared = 0;
        double worstOracle = 0.0;
        double worstDifference = 0.0;
        for (Mixture mixture : polarMixtures()) {
            var kernel = mixture.kernel();
            var workspace = kernel.newWorkspace();
            var output = kernel.newEvaluation();
            int n = mixture.size();
            for (double[] state : POLAR_STATES) {
                double t = state[0];
                double p = state[1];
                for (double[] x : mixture.compositions) {
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        kernel.evaluate(t, p, x, root, workspace, output);
                        String at = mixture.name + " T=" + t + " P=" + p + " " + root;
                        double z = output.compressibility();
                        var oracle = new Oracle(mixture, t, x);
                        assertEquals(oracle.b, output.bMix(), 1.0e-14 * oracle.b, at + " b");
                        assertEquals(oracle.a, output.aMix(), 1.0e-11 * Math.abs(oracle.a), at + " a");
                        double reducedA = oracle.a * p / (R * R * t * t);
                        double reducedB = oracle.b * p / (R * t);
                        double residual = ((z - (1.0 - reducedB)) * z + (reducedA - 3.0 * reducedB * reducedB - 2.0 * reducedB)) * z
                                - (reducedA * reducedB - reducedB * reducedB - reducedB * reducedB * reducedB);
                        assertTrue(Math.abs(residual) < 1.0e-11 * Math.max(1.0, z * z * z), at + " cubic residual " + residual);
                        double[] expected = oracle.logPhi(p, z);
                        double scale = 1.0;
                        for (double v : expected) scale = Math.max(scale, Math.abs(v));
                        for (int i = 0; i < n; i++) {
                            double deviation = Math.abs(expected[i] - output.logFugacityCoefficient(i)) / scale;
                            worstOracle = Math.max(worstOracle, deviation);
                            assertTrue(deviation < 1.0e-11, at + " ln phi_" + i + " oracle " + expected[i]
                                    + " kernel " + output.logFugacityCoefficient(i));
                            compared++;
                        }
                        // ln phi_i = d(n g^R/RT)/dn_i at fixed T, p, from the oracle's g^E alone (not its ln gamma).
                        double h = 1.0e-5;
                        for (int i = 0; i < n; i++) {
                            double[] up = Oracle.normalized(x);
                            double[] down = up.clone();
                            up[i] += h;
                            down[i] -= h;
                            if (down[i] < 0.0) continue;
                            double zUp = Oracle.compressibility(mixture, t, p, up, root);
                            double zDown = Oracle.compressibility(mixture, t, p, down, root);
                            if (Math.abs(zUp - z) > 1e-3 * z || Math.abs(zDown - z) > 1e-3 * z) continue;
                            double derivative = (Oracle.totalResidualGibbs(mixture, t, p, up, root)
                                    - Oracle.totalResidualGibbs(mixture, t, p, down, root)) / (2.0 * h);
                            double deviation = Math.abs(derivative - output.logFugacityCoefficient(i)) / scale;
                            worstDifference = Math.max(worstDifference, deviation);
                            assertTrue(deviation < 1.0e-7, at + " d(n g^R/RT)/dn_" + i + " " + derivative
                                    + " vs ln phi " + output.logFugacityCoefficient(i));
                            compared++;
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "HURON_VIDAL ln phi: %d values; oracle within %.1e, d(n g^R/RT)/dn within %.1e%n",
                compared, worstOracle, worstDifference);
        assertTrue(compared > 300, "too few comparisons: " + compared);
    }

    @Test void nrtlDerivativesMatchCentralDifferencesAndIdentities() {
        var worst = new LinkedHashMap<String, Worst>();
        int evaluations = 0;
        for (Mixture mixture : polarMixtures()) {
            var kernel = mixture.kernel();
            var workspace = kernel.newWorkspace();
            var derivatives = kernel.newDerivatives();
            int n = mixture.size();
            double[] rootDt = new double[n], rootDt2 = new double[n], crossDt = new double[n];
            for (double[] state : POLAR_STATES) {
                double t = state[0];
                double p = state[1];
                for (double[] raw : mixture.compositions) {
                    double[] x = Oracle.normalized(raw);
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        try {
                            kernel.evaluateDerivatives(t, p, x, root, workspace, derivatives);
                        } catch (IllegalStateException coalescing) { continue; }
                        evaluations++;
                        String at = mixture.name + " T=" + t + " P=" + p + " " + root;
                        var base = derivatives.evaluation();
                        double z = base.compressibility();
                        double hR = base.residualEnthalpyJoulesPerMol();
                        double[] dLogPhiDt = derivatives.dLogPhiDtView().clone();
                        double[] partial = derivatives.partialMolarResidualEnthalpyView().clone();
                        double dHrDt = derivatives.dResidualEnthalpyDt();
                        double[][] dLogPhiDn = new double[n][];
                        for (int i = 0; i < n; i++) dLogPhiDn[i] = derivatives.dLogPhiDnRowView(i).clone();
                        double secondDaDt = kernel.secondTemperatureDerivative(t, workspace, rootDt, rootDt2, crossDt);
                        double nScale = 0.0;
                        for (double[] row : dLogPhiDn) for (double v : row) nScale = Math.max(nScale, Math.abs(v));

                        double partialSum = 0.0;
                        double partialScale = Math.abs(hR);
                        for (int i = 0; i < n; i++) {
                            partialSum += x[i] * partial[i];
                            partialScale = Math.max(partialScale, Math.abs(x[i] * partial[i]));
                        }
                        record(worst, "sum x_i h^R_i = h^R", at, hR, partialSum, partialScale);
                        for (int j = 0; j < n; j++) {
                            double gibbsDuhem = 0.0;
                            for (int i = 0; i < n; i++) gibbsDuhem += x[i] * dLogPhiDn[i][j];
                            record(worst, "Gibbs-Duhem", at, 0.0, gibbsDuhem, nScale);
                            for (int i = 0; i < n; i++) record(worst, "symmetry", at, dLogPhiDn[j][i], dLogPhiDn[i][j], nScale);
                        }

                        if (keepsRoot(kernel, t, p, x, root, z, TEMPERATURE_STEP, -1)) {
                            var higher = evaluate(kernel, t + TEMPERATURE_STEP, p, x, root);
                            var lower = evaluate(kernel, t - TEMPERATURE_STEP, p, x, root);
                            record(worst, "da_mix/dT", at, (higher.aMix() - lower.aMix()) / (2 * TEMPERATURE_STEP),
                                    base.daMixDt(), Math.abs(base.daMixDt()));
                            record(worst, "d2a_mix/dT2", at, (higher.daMixDt() - lower.daMixDt()) / (2 * TEMPERATURE_STEP),
                                    secondDaDt, Math.abs(secondDaDt));
                            record(worst, "dh^R/dT", at, (higher.residualEnthalpyJoulesPerMol() - lower.residualEnthalpyJoulesPerMol())
                                    / (2 * TEMPERATURE_STEP), dHrDt, Math.max(Math.abs(dHrDt), R));
                            double scale = 0.0;
                            for (double v : dLogPhiDt) scale = Math.max(scale, Math.abs(v));
                            for (int i = 0; i < n; i++) {
                                record(worst, "d ln phi_i/dT", at, (higher.logFugacityCoefficient(i) - lower.logFugacityCoefficient(i))
                                        / (2 * TEMPERATURE_STEP), dLogPhiDt[i], scale);
                            }
                        }
                        for (int j = 0; j < n; j++) {
                            if (!(x[j] > 10 * MOLE_STEP) || !keepsRoot(kernel, t, p, x, root, z, 0.0, j)) continue;
                            double[] up = x.clone();
                            double[] down = x.clone();
                            up[j] += MOLE_STEP;
                            down[j] -= MOLE_STEP;
                            var higher = evaluate(kernel, t, p, up, root);
                            var lower = evaluate(kernel, t, p, down, root);
                            for (int i = 0; i < n; i++) {
                                record(worst, "d ln phi_i/dn_j", at, (higher.logFugacityCoefficient(i) - lower.logFugacityCoefficient(i))
                                        / (2 * MOLE_STEP), dLogPhiDn[i][j], nScale);
                            }
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "HURON_VIDAL derivatives vs central differences: %d phase evaluations%n", evaluations);
        worst.forEach((quantity, value) -> System.out.println("    " + String.format(Locale.ROOT, "%-22s", quantity) + value));
        assertTrue(evaluations >= 60, "too few evaluations: " + evaluations);
        for (String quantity : List.of("da_mix/dT", "d2a_mix/dT2", "dh^R/dT", "d ln phi_i/dT", "d ln phi_i/dn_j")) {
            assertTrue(worst.containsKey(quantity) && worst.get(quantity).count >= 40, quantity + " barely compared");
            assertTrue(worst.get(quantity).deviation <= DIFFERENCE_TOLERANCE, quantity + ": " + worst.get(quantity));
        }
        for (String identity : List.of("sum x_i h^R_i = h^R", "Gibbs-Duhem", "symmetry")) {
            assertTrue(worst.get(identity).deviation <= 1.0e-10, identity + ": " + worst.get(identity));
        }
    }

    @Test void reusedWorkspaceMatchesFreshWorkspacesBitForBit() {
        var mixture = polarMixtures().get(2);
        var kernel = mixture.kernel();
        var reused = kernel.newWorkspace();
        var reusedOutput = kernel.newDerivatives();
        var freshOutput = kernel.newDerivatives();
        int compared = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (double[] state : POLAR_STATES) {
                for (double[] x : mixture.compositions) {
                    try {
                        kernel.evaluateDerivatives(state[0], state[1], x, PengRobinsonKernel.Root.LIQUID, reused, reusedOutput);
                    } catch (IllegalStateException coalescing) { continue; }
                    kernel.evaluateDerivatives(state[0], state[1], x, PengRobinsonKernel.Root.LIQUID, kernel.newWorkspace(), freshOutput);
                    compared += same(freshOutput.evaluation().logFugacityCoefficientsView(),
                            reusedOutput.evaluation().logFugacityCoefficientsView(), "ln phi");
                    compared += same(freshOutput.dLogPhiDtView(), reusedOutput.dLogPhiDtView(), "d ln phi/dT");
                    compared += same(new double[] {freshOutput.dResidualEnthalpyDt()}, new double[] {reusedOutput.dResidualEnthalpyDt()}, "dh/dT");
                    for (int i = 0; i < mixture.size(); i++) {
                        compared += same(freshOutput.dLogPhiDnRowView(i), reusedOutput.dLogPhiDnRowView(i), "dn");
                    }
                }
            }
        }
        assertTrue(compared > 500, "compared " + compared);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Mathias-Copeman alpha
    // ---------------------------------------------------------------------------------------------------------------

    @Test void soaveCompoundsKeepTodaysArithmeticBitForBit() {
        double[] tc = slice(POLAR_TC, 3);
        double[] pc = slice(POLAR_PC, 3);
        double[] omega = slice(POLAR_OMEGA, 3);
        var today = new PengRobinsonKernel(tc, pc, omega, new double[3][3], 1.0, 3000.0, 1.0, 1.0e9);
        var withWater = PengRobinsonKernel.huronVidal(tc, pc, omega, HuronVidalExcess.builder(3).build(),
                new double[][] {null, WATER_ALPHA, null}, 1.0, 3000.0, 1.0, 1.0e9);
        var soaveOnly = PengRobinsonKernel.huronVidal(tc, pc, omega, HuronVidalExcess.builder(3).build(), null,
                1.0, 3000.0, 1.0, 1.0e9);
        int compared = 0;
        for (double t : new double[] {250.0, 300.0, 373.15, 514.71, 600.0, 647.096, 900.0}) {
            var expected = roots(today, t);
            var mixed = roots(withWater, t);
            var plain = roots(soaveOnly, t);
            for (int i = 0; i < 3; i++) {
                for (int v = 0; v < 3; v++) {
                    compared += same(new double[] {expected[v][i]}, new double[] {plain[v][i]}, "Soave-only kernel " + v + " at " + t);
                    if (i != 1) compared += same(new double[] {expected[v][i]}, new double[] {mixed[v][i]}, "Soave compound " + i + " at " + t);
                }
            }
            // At Tc both alphas are exactly 1.
            if (t != POLAR_TC[1]) assertNotEquals(expected[0][1], mixed[0][1], "water's alpha must be its own");
        }
        assertTrue(compared > 90);
    }

    @Test void kappaOnlyCoefficientsAreTheSoaveForm() {
        double[] tc = slice(POLAR_TC, 3);
        double[] pc = slice(POLAR_PC, 3);
        double[] omega = slice(POLAR_OMEGA, 3);
        var soave = PengRobinsonKernel.huronVidal(tc, pc, omega, HuronVidalExcess.builder(3).build(), null, 1.0, 3000.0, 1.0, 1.0e9);
        double[][] coefficients = new double[3][];
        for (int i = 0; i < 3; i++) coefficients[i] = new double[] {kappa(omega[i]), 0.0, 0.0};
        var copeman = PengRobinsonKernel.huronVidal(tc, pc, omega, HuronVidalExcess.builder(3).build(), coefficients,
                1.0, 3000.0, 1.0, 1.0e9);
        for (double t : new double[] {250.0, 400.0, 600.0, 900.0}) {
            var expected = roots(soave, t);
            var actual = roots(copeman, t);
            for (int v = 0; v < 3; v++) {
                for (int i = 0; i < 3; i++) {
                    assertEquals(expected[v][i], actual[v][i], 1.0e-13 * Math.abs(expected[v][i]), "quantity " + v + " of " + i + " at " + t);
                }
            }
        }
    }

    @Test void waterSaturationPressureAt100C() {
        var kernel = water();
        var workspace = kernel.newWorkspace();
        var liquid = kernel.newEvaluation();
        var vapour = kernel.newEvaluation();
        double t = 373.15;
        double p = 1.0e5;
        double[] x = {1.0};
        for (int iteration = 0; iteration < 200; iteration++) {
            kernel.evaluate(t, p, x, PengRobinsonKernel.Root.LIQUID, workspace, liquid);
            kernel.evaluate(t, p, x, PengRobinsonKernel.Root.VAPOR, workspace, vapour);
            double step = liquid.logFugacityCoefficient(0) - vapour.logFugacityCoefficient(0);
            p *= Math.exp(step);
            if (Math.abs(step) < 1.0e-13) break;
        }
        kernel.evaluate(t, p, x, PengRobinsonKernel.Root.LIQUID, workspace, liquid);
        kernel.evaluate(t, p, x, PengRobinsonKernel.Root.VAPOR, workspace, vapour);
        assertEquals(3, liquid.physicalRootCount());
        assertTrue(vapour.compressibility() > 10.0 * liquid.compressibility(), "two distinct roots");
        assertEquals(liquid.logFugacityCoefficient(0), vapour.logFugacityCoefficient(0), 1.0e-11);
        System.out.printf(Locale.ROOT, "Mathias-Copeman water at 373.15 K: p_sat = %.1f Pa (%.3f %% from 101418 Pa)%n",
                p, 100.0 * (p / 101418.0 - 1.0));
        assertEquals(101418.0, p, 0.002 * 101418.0);
    }

    @Test void mathiasCopemanIsContinuousAtTheCriticalTemperature() {
        var kernel = water();
        double tc = POLAR_TC[1];
        double[][] below = roots(kernel, tc * (1.0 - 1.0e-9));
        double[][] above = roots(kernel, tc * (1.0 + 1.0e-9));
        double aBelow = below[0][0] * below[0][0];
        double aAbove = above[0][0] * above[0][0];
        double slopeBelow = 2.0 * below[0][0] * below[1][0];
        double slopeAbove = 2.0 * above[0][0] * above[1][0];
        assertEquals(aBelow, aAbove, 1.0e-7 * aBelow, "a across Tc");
        assertEquals(slopeBelow, slopeAbove, 1.0e-6 * Math.abs(slopeBelow), "da/dT across Tc");
        // Above Tc only c1: a = a_c (1 + c1 u)^2.
        double t = 1.3 * tc;
        double criticalA = 0.45724 * R * R * tc * tc / POLAR_PC[1];
        double u = 1.0 - Math.sqrt(t / tc);
        double s = 1.0 + WATER_ALPHA[0] * u;
        double[][] hot = roots(kernel, t);
        assertEquals(criticalA * s * s, hot[0][0] * hot[0][0], 1.0e-14 * criticalA * s * s);
        // Both derivatives against central differences, on both sides.
        for (double temperature : new double[] {300.0, 500.0, 640.0, 700.0, 900.0}) {
            double h = 1.0e-3;
            double[][] centre = roots(kernel, temperature);
            double[][] up = roots(kernel, temperature + h);
            double[][] down = roots(kernel, temperature - h);
            assertEquals((up[0][0] - down[0][0]) / (2 * h), centre[1][0], 1.0e-7 * Math.abs(centre[1][0]), "d sqrt(a)/dT at " + temperature);
            assertEquals((up[1][0] - down[1][0]) / (2 * h), centre[2][0], 1.0e-6 * Math.abs(centre[2][0]), "d2 sqrt(a)/dT2 at " + temperature);
        }
    }

    private static PengRobinsonKernel water() {
        return PengRobinsonKernel.huronVidal(new double[] {POLAR_TC[1]}, new double[] {POLAR_PC[1]}, new double[] {POLAR_OMEGA[1]},
                HuronVidalExcess.builder(1).build(), new double[][] {WATER_ALPHA}, 1.0, 3000.0, 1.0, 1.0e9);
    }

    /** {@code sqrt(a_i)}, {@code d sqrt(a_i)/dT}, {@code d^2 sqrt(a_i)/dT^2} of a prepared fresh workspace. */
    private static double[][] roots(PengRobinsonKernel kernel, double t) {
        int n = kernel.componentCount();
        var workspace = kernel.newWorkspace();
        kernel.prepareTemperature(t, workspace);
        double[] rootDt = new double[n];
        double[] rootDt2 = new double[n];
        kernel.prepareRootDerivatives(t, workspace, rootDt, rootDt2);
        return new double[][] {workspace.attractionRootsView().clone(), rootDt, rootDt2};
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Validation
    // ---------------------------------------------------------------------------------------------------------------

    @Test void refusesInvalidModelsAndKernels() {
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(0));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).nrtl(0, 1, 1.0, 300, 1, 1, 400, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).nrtl(0, 1, -0.1, 300, 1, 1, 400, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).nrtl(0, 1, 0.3, 300, 1, 1, 300, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).nrtl(0, 1, 0.3, 300, Double.NaN, 1, 400, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).nrtl(0, 2, 0.3, 300, 1, 1, 400, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).classical(1, 1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).classical(0, 1, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).classical(0, 1, 0.1).nrtl(1, 0, 0.3, 300, 1, 1, 400, 1, 1));
        double[] tc = slice(POLAR_TC, 2), pc = slice(POLAR_PC, 2), omega = slice(POLAR_OMEGA, 2);
        assertThrows(IllegalArgumentException.class, () -> PengRobinsonKernel.huronVidal(tc, pc, omega,
                HuronVidalExcess.builder(3).build(), null, 1, 3000, 1, 1e9));
        assertThrows(IllegalArgumentException.class, () -> PengRobinsonKernel.huronVidal(tc, pc, omega,
                HuronVidalExcess.builder(2).build(), new double[][] {{1.0, 0.0}, null}, 1, 3000, 1, 1e9));
        assertThrows(IllegalArgumentException.class, () -> PengRobinsonKernel.huronVidal(tc, pc, omega,
                HuronVidalExcess.builder(2).build(), new double[][] {null}, 1, 3000, 1, 1e9));
        assertThrows(IllegalArgumentException.class, () -> new PengRobinsonKernel(tc, pc, omega, new double[2][2],
                1, 3000, 1, 1e9, PengRobinsonKernel.Mixing.HURON_VIDAL));
        // The stored model reads back what was declared.
        var model = HuronVidalExcess.builder(2).nrtl(0, 1, 0.3, 300, 1000, 2000, 400, 1500, 1000).build();
        assertEquals(0.3, model.alpha(1, 0));
        assertFalse(model.classical(0, 1));
        assertEquals(5.0, model.energySlope(0, 1), 1e-12);
        assertEquals(1000.0 - 5.0 * 300.0, model.energyIntercept(0, 1), 1e-9);
        assertEquals(-10.0, model.energySlope(1, 0), 1e-12);
        assertEquals(1, model.nrtlPairCount());
        assertEquals(0.6232252401402305, LAMBDA, 1e-15);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The oracle: the rule written out directly (after the reference script's lnphi)
    // ---------------------------------------------------------------------------------------------------------------

    private static final class Oracle {
        final Mixture mixture;
        final double t;
        final double[] x;
        final double[] ai;
        final double[] bi;
        final double[] logGamma;
        final double a;
        final double b;
        final double excess;

        Oracle(Mixture mixture, double t, double[] raw) {
            this.mixture = mixture;
            this.t = t;
            this.x = normalized(raw);
            int n = mixture.size();
            ai = new double[n];
            bi = new double[n];
            for (int i = 0; i < n; i++) {
                double tc = mixture.tc[i];
                double u = 1.0 - Math.sqrt(t / tc);
                double[] c = mixture.alphaCoefficients == null ? null : mixture.alphaCoefficients[i];
                double s = c == null ? 1.0 + kappa(mixture.omega[i]) * u
                        : u < 0.0 ? 1.0 + c[0] * u : 1.0 + c[0] * u + c[1] * u * u + c[2] * u * u * u;
                ai[i] = 0.45724 * R * R * tc * tc / mixture.pc[i] * s * s;
                bi[i] = 0.07780 * R * tc / mixture.pc[i];
            }
            double[] sn = new double[n];
            double[] dn = new double[n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    sn[i] += x[j] * bi[j] * g(j, i) * tau(j, i);
                    dn[i] += x[j] * bi[j] * g(j, i);
                }
            }
            logGamma = new double[n];
            double ge = 0.0;
            double bm = 0.0;
            double linear = 0.0;
            for (int k = 0; k < n; k++) {
                double v = sn[k] / dn[k];
                for (int i = 0; i < n; i++) v += x[i] * bi[k] * g(k, i) * (tau(k, i) - sn[i] / dn[i]) / dn[i];
                logGamma[k] = v;
                ge += x[k] * sn[k] / dn[k];
                bm += x[k] * bi[k];
                linear += x[k] * ai[k] / bi[k];
            }
            excess = ge;
            b = bm;
            a = bm * (linear - R * t * ge / LAMBDA);
        }

        /** {@code dg_ji}: the energy of {@code j} around a centre {@code i}. */
        double energy(int j, int i) {
            if (i == j) return 0.0;
            for (Nrtl pair : mixture.pairs) {
                if (pair.i == j && pair.j == i) return line(pair.t1, pair.gij1, pair.t2, pair.gij2);
                if (pair.i == i && pair.j == j) return line(pair.t1, pair.gji1, pair.t2, pair.gji2);
            }
            double gii = -LAMBDA * ai[i] / bi[i];
            double gjj = -LAMBDA * ai[j] / bi[j];
            double gji = -2.0 * Math.sqrt(bi[i] * bi[j]) / (bi[i] + bi[j]) * Math.sqrt(gii * gjj) * (1.0 - mixture.k[i][j]);
            return gji - gii;
        }

        double line(double t1, double v1, double t2, double v2) { return v1 + (v2 - v1) / (t2 - t1) * (t - t1); }

        double alpha(int j, int i) {
            for (Nrtl pair : mixture.pairs) if ((pair.i == i && pair.j == j) || (pair.i == j && pair.j == i)) return pair.alpha;
            return 0.0;
        }

        double tau(int j, int i) { return energy(j, i) / (R * t); }
        double g(int j, int i) { return Math.exp(-alpha(j, i) * tau(j, i)); }

        double[] logPhi(double p, double z) {
            double reducedB = b * p / (R * t);
            double logRatio = Math.log((z + (1.0 + SQRT_TWO) * reducedB) / (z + (1.0 - SQRT_TWO) * reducedB));
            double[] result = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                result[i] = bi[i] / b * (z - 1.0) - Math.log(z - reducedB)
                        - (ai[i] / (bi[i] * R * t) - logGamma[i] / LAMBDA) / (2.0 * SQRT_TWO) * logRatio;
            }
            return result;
        }

        static double compressibility(Mixture mixture, double t, double p, double[] moles, PengRobinsonKernel.Root root) {
            var oracle = new Oracle(mixture, t, moles);
            return PengRobinsonKernel.selectRoot(oracle.a * p / (R * R * t * t), oracle.b * p / (R * t), root).selectedCompressibility();
        }

        /** {@code n g^R/(R T)} at the moles given. */
        static double totalResidualGibbs(Mixture mixture, double t, double p, double[] moles, PengRobinsonKernel.Root root) {
            var oracle = new Oracle(mixture, t, moles);
            double reducedA = oracle.a * p / (R * R * t * t);
            double reducedB = oracle.b * p / (R * t);
            double z = PengRobinsonKernel.selectRoot(reducedA, reducedB, root).selectedCompressibility();
            double total = 0.0;
            for (double v : moles) total += v;
            return total * (z - 1.0 - Math.log(z - reducedB) - reducedA / (2.0 * SQRT_TWO * reducedB)
                    * Math.log((z + (1.0 + SQRT_TWO) * reducedB) / (z + (1.0 - SQRT_TWO) * reducedB)));
        }

        static double[] normalized(double[] raw) {
            double total = 0.0;
            for (double v : raw) total += v;
            double[] result = new double[raw.length];
            for (int i = 0; i < raw.length; i++) result[i] = raw[i] / total;
            return result;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private static double kappa(double omega) {
        return omega <= 0.491
                ? 0.37464 + 1.54226 * omega - 0.26992 * omega * omega
                : 0.379642 + 1.48503 * omega - 0.164423 * omega * omega + 0.016666 * omega * omega * omega;
    }

    private static double[] slice(double[] values, int length) {
        double[] result = new double[length];
        System.arraycopy(values, 0, result, 0, length);
        return result;
    }

    private static void set(double[][] matrix, int i, int j, double value) {
        matrix[i][j] = value;
        matrix[j][i] = value;
    }

    private static int close(double expected, double actual, double scale, double tolerance, String what) {
        double denominator = Math.max(scale, Math.max(Math.abs(expected), Math.abs(actual)));
        assertTrue(Math.abs(expected - actual) <= tolerance * denominator, () -> what + ": " + expected + " vs " + actual);
        return 1;
    }

    private static int close(double[] expected, double[] actual, double tolerance, String what) {
        double scale = 0.0;
        for (double v : expected) scale = Math.max(scale, Math.abs(v));
        for (int i = 0; i < expected.length; i++) close(expected[i], actual[i], scale, tolerance, what + "[" + i + "]");
        return expected.length;
    }

    private static int same(double[] expected, double[] actual, String what) {
        for (int i = 0; i < expected.length; i++) {
            int at = i;
            assertEquals(Double.doubleToLongBits(expected[i]), Double.doubleToLongBits(actual[i]),
                    () -> what + "[" + at + "]: " + expected[at] + " vs " + actual[at]);
        }
        return expected.length;
    }

    private static PengRobinsonKernel.Evaluation evaluate(PengRobinsonKernel kernel, double t, double p, double[] x,
                                                           PengRobinsonKernel.Root root) {
        var output = kernel.newEvaluation();
        kernel.evaluate(t, p, x, root, kernel.newWorkspace(), output);
        return output;
    }

    /** Both probe endpoints stay on the root the base state is on (a derivative is a derivative of one branch). */
    private static boolean keepsRoot(PengRobinsonKernel kernel, double t, double p, double[] base, PengRobinsonKernel.Root root,
                                     double z, double temperatureStep, int component) {
        try {
            for (int sign = -1; sign <= 1; sign += 2) {
                double[] x = base.clone();
                if (component >= 0) x[component] += sign * MOLE_STEP;
                var probe = evaluate(kernel, t + sign * temperatureStep, p, x, root);
                if (Math.abs(probe.compressibility() - z) > 1e-3 * Math.abs(z)) return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private record Worst(double deviation, double expected, double actual, String at, int count) {
        @Override public String toString() {
            return String.format(Locale.ROOT, "%.2e over %d (expected %.6e, actual %.6e at %s)", deviation, count, expected, actual, at);
        }
    }

    private static void record(Map<String, Worst> worst, String quantity, String at, double expected, double actual, double scale) {
        assertTrue(Double.isFinite(expected) && Double.isFinite(actual), quantity + " must be finite at " + at);
        double denominator = Math.max(scale, Math.max(Math.abs(expected), Math.abs(actual)));
        double deviation = denominator == 0 ? 0 : Math.abs(expected - actual) / denominator;
        var previous = worst.get(quantity);
        if (previous == null) worst.put(quantity, new Worst(deviation, expected, actual, at, 1));
        else if (deviation > previous.deviation) worst.put(quantity, new Worst(deviation, expected, actual, at, previous.count + 1));
        else worst.put(quantity, new Worst(previous.deviation, previous.expected, previous.actual, previous.at, previous.count + 1));
    }
}

package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PengRobinsonKernel#huronVidal} and the Mathias-Copeman alpha: with every pair classical the rule is the
 * quadratic rule of a constant matrix to roundoff, in every value and derivative; on polar
 * mixtures with NRTL pairs {@code ln phi_i} matches an oracle written from the rule and the composition derivative of
 * an independent {@code n g^R/(R T)}; every temperature and composition derivative matches central differences of the
 * kernel's own values; Gibbs-Duhem, symmetry and {@code sum x_i h_i = H^R} hold; a compound without coefficients keeps
 * today's arithmetic bit for bit, and water's Mathias-Copeman alpha gives its 100 C vapour pressure. Classical pairs
 * whose {@code k_ij} depends on temperature (a line, or a {@link PairInteractions} model) match central differences, and
 * a list of E-PPR78 model pairs only is the kernel on that model.
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
     * component in a supersaturated vapour is (the error falls as h^2 from 1e-2 to 1e-4 there). 2.5e-6 since W3b step
     * 2c: with the held CO2/water line the 520 K, 5 MPa liquid left a truncation error of 1.4e-6 at 1e-5 (9e-8 at
     * 2.5e-6, the h^2 law), and the 1 bar vapour's noise at 2.5e-6 is 4.2e-7 (measured).
     */
    private static final double MOLE_STEP = 2.5e-6;
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
        assertFalse(rule.hasExcessPairs());
        return compareKernels(quadratic, rule, compositions, 1.0e-11);
    }

    /** Every value and derivative of {@code rule} against {@code quadratic}, relative to {@code tolerance}. */
    private static int compareKernels(PengRobinsonKernel quadratic, PengRobinsonKernel rule, double[][] compositions, double tolerance) {
        int n = quadratic.componentCount();
        var quadraticWorkspace = quadratic.newWorkspace();
        var ruleWorkspace = rule.newWorkspace();
        var expected = quadratic.newDerivatives();
        var actual = rule.newDerivatives();
        double[][] scratch = new double[6][n];
        int compared = 0;
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

    /**
     * W3b N8: a binary whose pair is classical is the quadratic rule under the kernel's conversion and under the
     * literature one alike (the oracle written both ways, the kernel against each); and on a kernel with NRTL pairs, a
     * phase where no NRTL partner of a present compound has an amount (benzene and CO2 beside absent ethanol and water)
     * gives the present compounds the quadratic values of the same pairs: value, compressibility and residual enthalpy
     * bit for bit, every derivative to roundoff.
     */
    @Test void aClassicalPairAndAPhaseWithoutAnActiveNrtlPairAreTheQuadraticRule() {
        double[][] k = new double[2][2];
        set(k, 0, 1, 0.08);
        var binary = new Mixture("benzene-CO2", new double[] {POLAR_TC[2], POLAR_TC[3]}, new double[] {POLAR_PC[2], POLAR_PC[3]},
                new double[] {POLAR_OMEGA[2], POLAR_OMEGA[3]}, null, List.of(), k,
                List.of(new double[] {0.3, 0.7}, new double[] {0.9, 0.1}));
        var kernel = binary.kernel();
        var workspace = kernel.newWorkspace();
        var output = kernel.newEvaluation();
        int compared = 0;
        for (double[] state : POLAR_STATES) {
            for (double[] x : binary.compositions) {
                for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                    kernel.evaluate(state[0], state[1], x, root, workspace, output);
                    for (boolean literature : new boolean[] {false, true}) {
                        var oracle = new Oracle(binary, state[0], x, literature);
                        assertEquals(oracle.a, output.aMix(), 1.0e-12 * oracle.a, "a, literature " + literature);
                        double[] expected = oracle.logPhi(state[1], output.compressibility());
                        for (int i = 0; i < 2; i++) {
                            assertEquals(expected[i], output.logFugacityCoefficient(i), 1.0e-11 * Math.max(1.0, Math.abs(expected[i])));
                            compared++;
                        }
                    }
                }
            }
        }
        double[][] matrix = new double[4][4];
        set(matrix, 1, 3, CO2_WATER_K);
        var wet = polarMixtures().get(2).kernel();
        var quadratic = new PengRobinsonKernel(POLAR_TC, POLAR_PC, POLAR_OMEGA, matrix, 1.0, 3000.0, 1.0, 1.0e9);
        assertTrue(wet.hasExcessPairs());
        var wetWorkspace = wet.newWorkspace();
        var quadraticWorkspace = quadratic.newWorkspace();
        var wetOutput = wet.newDerivatives();
        var quadraticOutput = quadratic.newDerivatives();
        double worst = 0.0;
        for (double[] state : POLAR_STATES) {
            for (double[] x : List.of(new double[] {0.0, 0.0, 0.6, 0.4}, new double[] {0.0, 0.0, 0.05, 0.95})) {
                for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                    wet.evaluateDerivatives(state[0], state[1], x, root, wetWorkspace, wetOutput);
                    quadratic.evaluateDerivatives(state[0], state[1], x, root, quadraticWorkspace, quadraticOutput);
                    var a = wetOutput.evaluation();
                    var b = quadraticOutput.evaluation();
                    assertEquals(b.aMix(), a.aMix(), 0.0);
                    assertEquals(b.daMixDt(), a.daMixDt(), 0.0);
                    assertEquals(b.compressibility(), a.compressibility(), 0.0);
                    assertEquals(b.residualEnthalpyJoulesPerMol(), a.residualEnthalpyJoulesPerMol(), 0.0);
                    for (int i = 2; i < 4; i++) {
                        assertEquals(b.logFugacityCoefficient(i), a.logFugacityCoefficient(i), 0.0);
                        worst = Math.max(worst, relative(quadraticOutput.dLogPhiDtView()[i], wetOutput.dLogPhiDtView()[i]));
                        for (int j = 2; j < 4; j++) {
                            worst = Math.max(worst, relative(quadraticOutput.dLogPhiDnRowView(i)[j], wetOutput.dLogPhiDnRowView(i)[j]));
                        }
                        compared += 4;
                    }
                    worst = Math.max(worst, relative(quadraticOutput.dResidualEnthalpyDt(), wetOutput.dResidualEnthalpyDt()));
                }
            }
        }
        System.out.printf(Locale.ROOT, "N8 reduction: %d values; derivatives of the dry phase within %.1e%n", compared, worst);
        assertTrue(worst < 1.0e-12, "dry-phase derivatives off by " + worst);
        assertTrue(compared > 100, "too few comparisons: " + compared);
    }

    private static double relative(double expected, double actual) {
        return Math.abs(expected - actual) / Math.max(1.0e-300, Math.max(1.0e-6, Math.abs(expected)));
    }

    private static final double[][] POLAR_STATES = {{300.0, 1.0e5}, {340.0, 1.0e5}, {373.0, 1.0e5}, {420.0, 5.0e5},
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
        for (Mixture mixture : polarMixtures()) evaluations += differences(mixture.kernel(), mixture.name, mixture.compositions, worst);
        System.out.printf(Locale.ROOT, "HURON_VIDAL derivatives vs central differences: %d phase evaluations%n", evaluations);
        assertDifferences(worst, evaluations);
    }

    private static void assertDifferences(Map<String, Worst> worst, int evaluations) {
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

    /** Every analytic derivative of {@code kernel} on {@link #POLAR_STATES} against central differences and identities. */
    private static int differences(PengRobinsonKernel kernel, String name, List<double[]> compositions, Map<String, Worst> worst) {
        int evaluations = 0;
        {
            var workspace = kernel.newWorkspace();
            var derivatives = kernel.newDerivatives();
            int n = kernel.componentCount();
            double[] rootDt = new double[n], rootDt2 = new double[n], crossDt = new double[n];
            for (double[] state : POLAR_STATES) {
                double t = state[0];
                double p = state[1];
                for (double[] raw : compositions) {
                    double[] x = Oracle.normalized(raw);
                    for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                        try {
                            kernel.evaluateDerivatives(t, p, x, root, workspace, derivatives);
                        } catch (IllegalStateException coalescing) { continue; }
                        evaluations++;
                        String at = name + " T=" + t + " P=" + p + " " + root;
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
        return evaluations;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Classical pairs whose k_ij depends on temperature
    // ---------------------------------------------------------------------------------------------------------------

    /** E-PPR78 group pairs used below, A and B in MPa (the bundled table's values). */
    private static final Map<String, double[]> GROUP_PAIRS = Map.ofEntries(
            Map.entry("CH4/CO2", new double[] {136.6, 214.8}), Map.entry("C2H6/CO2", new double[] {136.2, 235.7}),
            Map.entry("CH3/CO2", new double[] {144.8, 401.5}), Map.entry("CH2/CO2", new double[] {141.4, 237.1}),
            Map.entry("C2H6/CH4", new double[] {9.951, 13.73}), Map.entry("CH3/CH4", new double[] {28.48, 20.25}),
            Map.entry("CH2/CH4", new double[] {37.75, 74.81}), Map.entry("C2H6/CH3", new double[] {3.775, 8.922}),
            Map.entry("C2H6/CH2", new double[] {29.85, 65.88}), Map.entry("CH2/CH3", new double[] {65.54, 105.7}),
            Map.entry("CHaro/CO2", new double[] {98.48, 253.6}));

    /** Declares the E-PPR78 pair of two group decompositions on {@code builder}: weights from the group fractions. */
    private static void groupPair(PairInteractions.Builder builder, int i, int j, Map<String, Integer> first, Map<String, Integer> second) {
        var groups = new java.util.TreeSet<String>();
        groups.addAll(first.keySet());
        groups.addAll(second.keySet());
        var names = new ArrayList<>(groups);
        double totalFirst = first.values().stream().mapToInt(Integer::intValue).sum();
        double totalSecond = second.values().stream().mapToInt(Integer::intValue).sum();
        var weights = new ArrayList<Double>();
        var a = new ArrayList<Double>();
        var exponents = new ArrayList<Double>();
        for (int k = 0; k < names.size(); k++) {
            for (int l = k + 1; l < names.size(); l++) {
                String gk = names.get(k), gl = names.get(l);
                double dk = first.getOrDefault(gk, 0) / totalFirst - second.getOrDefault(gk, 0) / totalSecond;
                double dl = first.getOrDefault(gl, 0) / totalFirst - second.getOrDefault(gl, 0) / totalSecond;
                if (dk * dl == 0.0) continue;
                double[] parameters = GROUP_PAIRS.get(gk.compareTo(gl) < 0 ? gk + "/" + gl : gl + "/" + gk);
                weights.add(-(dk * dl));
                a.add(parameters[0] * 1.0e6);
                exponents.add(parameters[1] / parameters[0] - 1.0);
            }
        }
        builder.groupTerms(i, j, weights.stream().mapToDouble(Double::doubleValue).toArray(),
                a.stream().mapToDouble(Double::doubleValue).toArray(), exponents.stream().mapToDouble(Double::doubleValue).toArray());
    }

    @Test void interactionModelPairsEqualTheTemperatureDependentPlan() {
        List<Map<String, Integer>> groups = List.of(Map.of("CH4", 1), Map.of("C2H6", 1), Map.of("CH3", 2, "CH2", 1),
                Map.of("CH3", 2, "CH2", 8), Map.of("CO2", 1));
        var builder = PairInteractions.builder(5);
        for (int i = 0; i < 5; i++) for (int j = i + 1; j < 5; j++) groupPair(builder, i, j, groups.get(i), groups.get(j));
        var model = builder.build();
        assertTrue(model.temperatureDependent());
        var plan = new PengRobinsonKernel(HC_TC, HC_PC, HC_OMEGA, model, 1.0, 3000.0, 1.0, 1.0e9);
        var excess = HuronVidalExcess.builder(5).interactions(model).build();
        assertFalse(excess.rankOne());
        assertTrue(excess.interactionPair(1, 3) >= 0 && excess.interactionPair(3, 1) == excess.interactionPair(1, 3));
        var rule = PengRobinsonKernel.huronVidal(HC_TC, HC_PC, HC_OMEGA, excess, null, 1.0, 3000.0, 1.0, 1.0e9);
        int compared = compareKernels(plan, rule, new double[][] {
                {0.5, 0.2, 0.1, 0.05, 0.15}, {0.05, 0.1, 0.2, 0.6, 0.05}, {0.2, 0.2, 0.2, 0.2, 0.2}}, 1.0e-10);
        System.out.println("HURON_VIDAL with E-PPR78 model pairs vs TEMPERATURE_DEPENDENT_PAIRS: " + compared + " values within 1e-10");
        assertTrue(compared > 1500, "too few comparisons: " + compared);
    }

    @Test void temperatureDependentClassicalPairsMatchCentralDifferences() {
        // Ethanol, water (Mathias-Copeman), benzene, CO2: the three NRTL pairs; CO2-water linear in T; CO2 with ethanol
        // (as its alkyl groups) and with benzene from an E-PPR78 model.
        var builder = PairInteractions.builder(4);
        groupPair(builder, 0, 3, Map.of("CH3", 1, "CH2", 1), Map.of("CO2", 1));
        groupPair(builder, 2, 3, Map.of("CHaro", 6), Map.of("CO2", 1));
        var model = builder.build();
        var excess = HuronVidalExcess.builder(4);
        for (Nrtl pair : POLAR_PAIRS) excess.nrtl(pair.i, pair.j, pair.alpha, pair.t1, pair.gij1, pair.gji1, pair.t2, pair.gij2, pair.gji2);
        var built = excess.classicalLinear(1, 3, 298.15, 0.05, 373.15, 0.15).interactions(model).build();
        assertEquals(0.05 - (0.10 / 75.0) * 298.15, built.interaction(3, 1), 1e-15);
        assertEquals(0.10 / 75.0, built.interactionSlope(1, 3), 1e-15);
        var kernel = PengRobinsonKernel.huronVidal(POLAR_TC, POLAR_PC, POLAR_OMEGA, built, POLAR_ALPHA, 1.0, 3000.0, 1.0, 1.0e9);
        var worst = new LinkedHashMap<String, Worst>();
        int evaluations = differences(kernel, "k(T) pairs", List.of(new double[] {0.3, 0.45, 0.2, 0.05},
                new double[] {0.05, 0.1, 0.05, 0.8}, new double[] {0.25, 0.25, 0.25, 0.25}, new double[] {0.1, 0.7, 0.1, 0.1},
                new double[] {0.6, 0.1, 0.2, 0.1}), worst);
        System.out.printf(Locale.ROOT, "HURON_VIDAL with linear and model k(T) vs central differences: %d phase evaluations%n", evaluations);
        assertDifferences(worst, evaluations);
        // A line through equal values is the constant pair, bit for bit.
        var constant = PengRobinsonKernel.huronVidal(slice(POLAR_TC, 2), slice(POLAR_PC, 2), slice(POLAR_OMEGA, 2),
                HuronVidalExcess.builder(2).classical(0, 1, 0.07).build(), null, 1.0, 3000.0, 1.0, 1.0e9);
        var line = PengRobinsonKernel.huronVidal(slice(POLAR_TC, 2), slice(POLAR_PC, 2), slice(POLAR_OMEGA, 2),
                HuronVidalExcess.builder(2).classicalLinear(0, 1, 300.0, 0.07, 400.0, 0.07).build(), null, 1.0, 3000.0, 1.0, 1.0e9);
        var expected = constant.newDerivatives();
        var actual = line.newDerivatives();
        constant.evaluateDerivatives(350.0, 1.0e5, new double[] {0.4, 0.6}, PengRobinsonKernel.Root.LIQUID, constant.newWorkspace(), expected);
        line.evaluateDerivatives(350.0, 1.0e5, new double[] {0.4, 0.6}, PengRobinsonKernel.Root.LIQUID, line.newWorkspace(), actual);
        same(expected.evaluation().logFugacityCoefficientsView(), actual.evaluation().logFugacityCoefficientsView(), "ln phi");
        same(expected.dLogPhiDtView(), actual.dLogPhiDtView(), "d ln phi/dT");
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(2).classicalLinear(0, 1, 300, 0.1, 300, 0.2));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(4).classical(0, 3, 0.1).interactions(model));
        assertThrows(IllegalArgumentException.class, () -> HuronVidalExcess.builder(3).interactions(model));
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
        /**
         * Whether a classical pair takes the literature conversion {@code g_ji = -2 sqrt(b_i b_j)/(b_i + b_j)
         * sqrt(g_ii g_jj)(1 - k_ij)} instead of the kernel's {@code dg_ji = Lambda [a_i/b_i - (1 - k_ij) s_i s_j/b_j]}
         * (W3b N8, option B). Both are the quadratic rule where every pair is classical.
         */
        final boolean literature;

        Oracle(Mixture mixture, double t, double[] raw) { this(mixture, t, raw, false); }

        Oracle(Mixture mixture, double t, double[] raw, boolean literature) { this(mixture, t, raw, literature, false); }

        /** {@code extended}: the rule's sums in 50-digit decimal arithmetic on the same double energies and G. */
        Oracle(Mixture mixture, double t, double[] raw, boolean literature, boolean extended) {
            this.literature = literature;
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
            if (extended) {
                MathContext digits = new MathContext(50);
                BigDecimal[] bsn = new BigDecimal[n], bdn = new BigDecimal[n];
                for (int i = 0; i < n; i++) {
                    bsn[i] = BigDecimal.ZERO;
                    bdn[i] = BigDecimal.ZERO;
                    for (int j = 0; j < n; j++) {
                        BigDecimal w = new BigDecimal(x[j]).multiply(new BigDecimal(bi[j]), digits).multiply(new BigDecimal(g(j, i)), digits);
                        bsn[i] = bsn[i].add(w.multiply(new BigDecimal(tau(j, i)), digits), digits);
                        bdn[i] = bdn[i].add(w, digits);
                    }
                }
                logGamma = new double[n];
                BigDecimal bge = BigDecimal.ZERO;
                double bm = 0.0, linear = 0.0;
                for (int k = 0; k < n; k++) {
                    BigDecimal thetaK = bsn[k].divide(bdn[k], digits);
                    BigDecimal v = thetaK;
                    for (int i = 0; i < n; i++) {
                        if (x[i] == 0.0) continue;
                        BigDecimal thetaI = bsn[i].divide(bdn[i], digits);
                        BigDecimal term = new BigDecimal(x[i]).multiply(new BigDecimal(bi[k]), digits).multiply(new BigDecimal(g(k, i)), digits)
                                .multiply(new BigDecimal(tau(k, i)).subtract(thetaI, digits), digits).divide(bdn[i], digits);
                        v = v.add(term, digits);
                    }
                    logGamma[k] = v.doubleValue();
                    bge = bge.add(new BigDecimal(x[k]).multiply(thetaK, digits), digits);
                    bm += x[k] * bi[k];
                    linear += x[k] * ai[k] / bi[k];
                }
                excess = bge.doubleValue();
                b = bm;
                a = bm * (linear - R * t * excess / LAMBDA);
                return;
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
            if (!literature) return LAMBDA * (ai[i] / bi[i] - (1.0 - mixture.k[i][j]) * Math.sqrt(ai[i] * ai[j]) / bi[j]);
            double gii = -LAMBDA * ai[i] / bi[i];
            double gjj = -LAMBDA * ai[j] / bi[j];
            double gji = -2.0 * Math.sqrt(bi[i] * bi[j]) / (bi[i] + bi[j]) * Math.sqrt(gii * gjj) * (1.0 - mixture.k[i][j]);
            return gji - gii;
        }

        /** The held line (W3b step 2d): linear between the ends; past one, the integral of a slope fading to zero over 150 K along a smoothstep. */
        double line(double t1, double v1, double t2, double v2) {
            double s = (v2 - v1) / (t2 - t1), low = Math.min(t1, t2), high = Math.max(t1, t2);
            double at = Math.max(low, Math.min(high, t));
            double value = v1 + s * (at - t1);
            double x = Math.min(Math.abs(t - at), 150.0) / 150.0;
            return value + Math.signum(t - at) * s * 150.0 * (x - x * x * x + 0.5 * x * x * x * x);
        }

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

    /**
     * W3B_NETWORK_PLAN.md section 11: a centre whose cell partners hold nearly all of {@code b} with a small {@code G}
     * (water around a heavy cut, {@code dg} 512.8 kJ/mol at 298 K and alpha 0.1675: {@code G} near 1e-15) has its
     * classical part formed directly over its non-partners. In four phases (pure water with the cut and hexane absent,
     * water with their traces, the cut's oil, a hexane-rich phase) at three states and both roots, ln phi agrees to
     * 1e-12 with the plain sums {@code sum_j x_j b_j G_ji} of the rule taken in 50-digit decimal arithmetic.
     */
    @Test void aCentreAmongDominantPartnersAgreesWithThePlainSumsInExtendedPrecision() {
        double[][] k = new double[3][3];
        set(k, 0, 2, 0.5);
        var mixture = new Mixture("water-heavy cut-hexane", new double[] {647.096, 850.0, 507.6}, new double[] {22.064e6, 1.3e6, 3.025e6},
                new double[] {0.3443, 0.9, 0.301}, new double[][] {WATER_ALPHA, null, null},
                List.of(new Nrtl(0, 1, 0.1675, 298.15, 512800.0, 19058.3, 373.15, 324300.0, 22381.0)), k,
                List.of(new double[] {1.0, 0.0, 0.0}, new double[] {1.0 - 1.0e-9 - 1.0e-6, 1.0e-9, 1.0e-6},
                        new double[] {1.0e-3, 0.5, 0.499}, new double[] {0.05, 1.0e-7, 0.95}));
        var kernel = mixture.kernel();
        var workspace = kernel.newWorkspace();
        var output = kernel.newEvaluation();
        double worst = 0.0;
        int compared = 0;
        for (double[] state : new double[][] {{298.15, 1.0e5}, {373.15, 1.0e5}, {450.0, 3.0e7}}) {
            for (double[] x : mixture.compositions) {
                for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                    kernel.evaluate(state[0], state[1], x, root, workspace, output);
                    var oracle = new Oracle(mixture, state[0], x, false, true);
                    double[] expected = oracle.logPhi(state[1], output.compressibility());
                    for (int i = 0; i < 3; i++) {
                        double actual = output.logFugacityCoefficient(i);
                        assertTrue(Double.isFinite(actual), "finite ln phi_" + i);
                        double deviation = Math.abs(actual - expected[i]) / Math.max(1.0, Math.abs(expected[i]));
                        worst = Math.max(worst, deviation);
                        assertTrue(deviation < 1.0e-12, state[0] + " K, " + state[1] + " Pa, " + java.util.Arrays.toString(x) + " " + root
                                + ": ln phi_" + i + " " + actual + " against " + expected[i]);
                        compared++;
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "dominant partners: %d ln phi against 50-digit plain sums, worst %.1e%n", compared, worst);
    }

    /**
     * W3B_NETWORK_PLAN.md section 11 on the refinery network: every crude cut absent in pure water has a finite ln phi from
     * 90 to 800 K, smooth along the one root at 30 MPa (above water's critical pressure, where no root ends): the second
     * difference over 0.5 K stays below 0.05 (the cancellation left steps of 50 between 298.15 and 298.2 K, and NaN at 285 K).
     */
    @Test void anAbsentCutInPureWaterHasAFiniteSmoothLogFugacity() {
        var model = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel().hydrocarbon;
        int n = model.componentCount();
        double[] water = new double[n + 1];
        water[n] = 1.0;
        double worst = 0.0;
        String worstAt = "";
        int cuts = 0;
        for (int c = 0; c < n; c++) {
            if (!model.components().get(c).startsWith("crude_")) continue;
            cuts++;
            double previous = Double.NaN, beforePrevious = Double.NaN;
            for (int step = 0; step <= 1420; step++) {
                double t = 90.0 + 0.5 * step;
                double value = model.phase(t, 3.0e7, water, PengRobinsonKernel.Root.LIQUID).logFugacity()[c];
                assertTrue(Double.isFinite(value), model.components().get(c) + " at " + t + " K");
                if (step >= 2) {
                    double curvature = Math.abs(value - 2.0 * previous + beforePrevious);
                    if (curvature > worst) {
                        worst = curvature;
                        worstAt = model.components().get(c) + " at " + t + " K";
                    }
                }
                beforePrevious = previous;
                previous = value;
            }
        }
        System.out.printf(Locale.ROOT, "absent cuts in pure water, 30 MPa, 90 to 800 K: %d cuts, worst second difference %.2e (%s)%n", cuts, worst, worstAt);
        assertTrue(cuts >= 12, "the refinery's cuts");
        assertTrue(worst < 0.05, worstAt + ": " + worst);
    }

    /**
     * KERNEL_COST_PLAN.md Part A, the gate: on the refinery network's list, a workspace that carries a support (its
     * feed's compounds) gives every output on the support bit for bit as the whole-list workspace does, at a liquid and a
     * vapour root, for five supports; outside the support every output is zero (NaN under the test switch), an amount
     * outside it is refused, and one workspace and one output reused across the supports, in both directions, agree too.
     */
    @Test void aCarriedSupportKeepsEveryOutputOnItBitForBit() {
        var model = com.wormzjl.icheme.fluid.support.FluidTestSupport.networkModel().hydrocarbon;
        PengRobinsonKernel kernel = model.evaluator().kernel();
        List<String> species = model.species();
        int n = kernel.componentCount();
        assertEquals(species.size(), n);
        assertTrue(kernel.hasExcessPairs(), "the network list has NRTL cells");
        int water = species.indexOf("Water"), nitrogen = species.indexOf("Nitrogen");
        int ethanol = species.indexOf("Ethanol"), ammonia = species.indexOf("Ammonia");
        int cut = -1;
        for (int i = 0; i < n && cut < 0; i++) if (species.get(i).startsWith("crude_")) cut = i;
        assertTrue(water >= 0 && nitrogen >= 0 && ethanol >= 0 && ammonia >= 0 && cut >= 0, species.toString());
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
        var reused = kernel.newWorkspace();
        var reusedOutput = kernel.newDerivatives();
        int compared = 0;
        List<int[]> order = new ArrayList<>(supports);
        // Per root (one temperature), the supports grow on the first pass and shrink on the second: the reused workspace
        // prepares only what a grown support adds at the same temperature.
        for (int pass = 0; pass < 2; pass++) {
            for (int r = 0; r < roots.length; r++) {
                for (int[] support : order) {
                    double[] feed = new double[n];
                    for (int at = 0; at < support.length; at++) feed[support[at]] = 1.0 + (support[at] * 37 % 11);
                    feed[water] += 3.0 * support.length;
                    double t = states[r][0], p = states[r][1];
                    var whole = kernel.newWorkspace();
                    var expected = kernel.newDerivatives();
                    kernel.evaluateDerivatives(t, p, feed, roots[r], whole, expected);
                    double[] wholeRootDt = new double[n], wholeRootDt2 = new double[n], wholeCrossDt = new double[n];
                    double wholeSecond = kernel.secondTemperatureDerivative(t, whole, wholeRootDt, wholeRootDt2, wholeCrossDt);
                    var wholeValue = kernel.newEvaluation();
                    kernel.evaluate(t, p, feed, roots[r], kernel.newWorkspace(), wholeValue);

                    var fresh = kernel.newWorkspace();
                    fresh.carry(feed);
                    var actual = kernel.newDerivatives();
                    kernel.evaluateDerivatives(t, p, feed, roots[r], fresh, actual);
                    double[] rootDt = new double[n], rootDt2 = new double[n], crossDt = new double[n];
                    double second = kernel.secondTemperatureDerivative(t, fresh, rootDt, rootDt2, crossDt);
                    reused.carry(feed);
                    kernel.evaluateDerivatives(t, p, feed, roots[r], reused, reusedOutput);
                    var value = kernel.newEvaluation();
                    var valueSpace = kernel.newWorkspace();
                    valueSpace.carry(feed);
                    kernel.evaluate(t, p, feed, roots[r], valueSpace, value);
                    String at = support.length + " compounds, " + roots[r];
                    for (var got : List.of(actual, reusedOutput)) {
                        compared += sameOn(support, expected, got, at);
                        compared += sameOutside(support, got, n, 0.0, at);
                        assertEquals(Double.doubleToLongBits(expected.dResidualEnthalpyDt()), Double.doubleToLongBits(got.dResidualEnthalpyDt()), at + ": dh/dT");
                    }
                    assertEquals(Double.doubleToLongBits(wholeSecond), Double.doubleToLongBits(second), at + ": d2a/dT2");
                    for (int i : support) {
                        assertEquals(Double.doubleToLongBits(wholeValue.logFugacityCoefficient(i)), Double.doubleToLongBits(value.logFugacityCoefficient(i)), at + ": evaluate ln phi");
                        assertEquals(Double.doubleToLongBits(wholeCrossDt[i]), Double.doubleToLongBits(crossDt[i]), at + ": crossDt");
                    }
                    assertEquals(Double.doubleToLongBits(wholeValue.compressibility()), Double.doubleToLongBits(value.compressibility()), at + ": evaluate Z");
                }
            }
            java.util.Collections.reverse(order);
        }
        // An amount outside the support is refused; the test switch marks the outputs outside it.
        double[] pair = new double[n];
        pair[water] = 1.0;
        pair[nitrogen] = 1.0;
        var narrow = kernel.newWorkspace();
        narrow.carry(pair);
        double[] wider = pair.clone();
        wider[ethanol] = 0.5;
        assertThrows(IllegalArgumentException.class,
                () -> kernel.evaluate(320.0, 2.0e5, wider, PengRobinsonKernel.Root.LIQUID, narrow, kernel.newEvaluation()));
        PengRobinsonKernel.fillOutsideSupportWithNaN(true);
        try {
            var marked = kernel.newDerivatives();
            kernel.evaluateDerivatives(320.0, 2.0e5, pair, PengRobinsonKernel.Root.LIQUID, narrow, marked);
            compared += sameOutside(new int[] {water, nitrogen}, marked, n, Double.NaN, "NaN switch");
        } finally {
            PengRobinsonKernel.fillOutsideSupportWithNaN(false);
        }
        var cleared = kernel.newDerivatives();
        kernel.evaluateDerivatives(320.0, 2.0e5, pair, PengRobinsonKernel.Root.LIQUID, narrow, cleared);
        compared += sameOutside(new int[] {water, nitrogen}, cleared, n, 0.0, "after the NaN switch");
        System.out.printf(Locale.ROOT, "carried supports: %d outputs bit for bit on %d supports of %d compounds%n", compared, supports.size(), n);
    }

    private static int sameOn(int[] support, PengRobinsonKernel.Derivatives expected, PengRobinsonKernel.Derivatives actual, String at) {
        var e = expected.evaluation();
        var a = actual.evaluation();
        assertEquals(Double.doubleToLongBits(e.compressibility()), Double.doubleToLongBits(a.compressibility()), at + ": Z");
        assertEquals(Double.doubleToLongBits(e.residualEnthalpyJoulesPerMol()), Double.doubleToLongBits(a.residualEnthalpyJoulesPerMol()), at + ": h^R");
        assertEquals(Double.doubleToLongBits(e.aMix()), Double.doubleToLongBits(a.aMix()), at + ": a");
        assertEquals(Double.doubleToLongBits(e.bMix()), Double.doubleToLongBits(a.bMix()), at + ": b");
        assertEquals(Double.doubleToLongBits(e.daMixDt()), Double.doubleToLongBits(a.daMixDt()), at + ": da/dT");
        int count = 5;
        for (int i : support) {
            assertEquals(Double.doubleToLongBits(e.logFugacityCoefficient(i)), Double.doubleToLongBits(a.logFugacityCoefficient(i)), at + ": ln phi " + i);
            assertEquals(Double.doubleToLongBits(expected.dLogPhiDtView()[i]), Double.doubleToLongBits(actual.dLogPhiDtView()[i]), at + ": d ln phi/dT " + i);
            assertEquals(Double.doubleToLongBits(expected.partialMolarResidualEnthalpyView()[i]),
                    Double.doubleToLongBits(actual.partialMolarResidualEnthalpyView()[i]), at + ": partial h " + i);
            count += 3;
            for (int j : support) {
                assertEquals(Double.doubleToLongBits(expected.dLogPhiDnRowView(i)[j]), Double.doubleToLongBits(actual.dLogPhiDnRowView(i)[j]),
                        at + ": d ln phi/dn " + i + "," + j);
                count++;
            }
        }
        return count;
    }

    private static int sameOutside(int[] support, PengRobinsonKernel.Derivatives output, int n, double fill, String at) {
        boolean[] carried = new boolean[n];
        for (int i : support) carried[i] = true;
        int count = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (carried[i] && carried[j]) continue;
                assertEquals(Double.doubleToLongBits(fill), Double.doubleToLongBits(output.dLogPhiDnRowView(i)[j]), at + ": outside d ln phi/dn " + i + "," + j);
                count++;
            }
            if (carried[i]) continue;
            assertEquals(Double.doubleToLongBits(fill), Double.doubleToLongBits(output.evaluation().logFugacityCoefficient(i)), at + ": outside ln phi " + i);
            assertEquals(Double.doubleToLongBits(fill), Double.doubleToLongBits(output.dLogPhiDtView()[i]), at + ": outside d ln phi/dT " + i);
            assertEquals(Double.doubleToLongBits(fill), Double.doubleToLongBits(output.partialMolarResidualEnthalpyView()[i]), at + ": outside partial h " + i);
            count += 3;
        }
        return count;
    }

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

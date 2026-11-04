package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability.Result;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability.Verdict;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The tangent-plane stability test against independent answers: the kernel's own equal-fugacity pressure for a
 * pure fluid, a brute-force tangent-plane minimum over a fine composition grid for binaries, and the network
 * flash's phase count for the network package.
 */
class TangentPlaneStabilityTest {
    static final String NETWORK = "icheme:tjl20_methane_nitrogen";
    private static final double EPSILON = TangentPlaneStability.Settings.DEFAULT.instabilityTolerance();

    /** A kernel over the named components of the network package, in the given order, with its interactions. */
    static PengRobinsonKernel kernel(PengRobinsonKernel.Mixing mixing, String... names) {
        var pkg = MaterialCatalog.bundled().requirePackage(NETWORK);
        List<String> components = names.length == 0 ? pkg.components() : List.of(names);
        int n = components.size();
        double[] tc = new double[n];
        double[] pc = new double[n];
        double[] omega = new double[n];
        double[][] interactions = new double[n][n];
        for (int a = 0; a < n; a++) {
            int i = pkg.components().indexOf(components.get(a));
            var pr = pkg.properties().get(i).pr();
            tc[a] = pr.criticalTemperature();
            pc[a] = pr.criticalPressure();
            omega[a] = pr.acentricFactor();
            for (int b = 0; b < n; b++) {
                interactions[a][b] = pkg.interactions().get(i).get(pkg.components().indexOf(components.get(b)));
            }
        }
        // An open domain, as the translated evaluator builds it: the package's range is the caller's business.
        return new PengRobinsonKernel(tc, pc, omega, interactions,
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE, mixing);
    }

    /** The Tia Juana light assay of the network package plus 5 mol% nitrogen: every component present. */
    static double[] crudeWithNitrogen() {
        var pkg = MaterialCatalog.bundled().requirePackage(NETWORK);
        var assay = pkg.assays().get("icheme:tia_juana_light_methane");
        double[] z = new double[pkg.components().size()];
        for (int c = 0; c < assay.components().size(); c++) {
            z[pkg.components().indexOf(assay.components().get(c))] = assay.amounts().get(c);
        }
        z[pkg.components().indexOf("Nitrogen")] = 0.05;
        return z;
    }

    // (a) Pure nitrogen at its normal boiling point.

    @Test
    void pureNitrogenSwitchesFromVapourToLiquidAtTheKernelsEqualFugacityPressure() {
        var kernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Nitrogen");
        var test = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT);
        double t = 77.355;
        double[] pure = {1.0};

        Result low = test.test(t, 90_000.0, pure);
        Result high = test.test(t, 120_000.0, pure);
        assertEquals(Verdict.STABLE, low.verdict());
        assertEquals(Root.VAPOR, low.feedRoot());
        assertEquals(Root.LIQUID, low.trialRoot());
        assertTrue(low.minimumTangentPlaneDistance() > 0.0);
        assertEquals(Verdict.STABLE, high.verdict());
        assertEquals(Root.LIQUID, high.feedRoot());
        assertEquals(Root.VAPOR, high.trialRoot());
        assertTrue(high.minimumTangentPlaneDistance() > 0.0);

        // The kernel's own saturation pressure: where the two roots' fugacities are equal.
        var workspace = kernel.newWorkspace();
        var liquid = kernel.newEvaluation();
        var vapour = kernel.newEvaluation();
        DoubleUnaryOperator gap = p -> {
            kernel.evaluate(t, p, pure, Root.LIQUID, workspace, liquid);
            kernel.evaluate(t, p, pure, Root.VAPOR, workspace, vapour);
            assertEquals(3, vapour.physicalRootCount());
            return liquid.logFugacityCoefficient(0) - vapour.logFugacityCoefficient(0);
        };
        double below = 90_000.0;
        double above = 120_000.0;
        assertTrue(gap.applyAsDouble(below) > 0.0 && gap.applyAsDouble(above) < 0.0);
        for (int iteration = 0; iteration < 200 && Math.nextUp(below) < above; iteration++) {
            double middle = 0.5 * (below + above);
            if (gap.applyAsDouble(middle) > 0.0) below = middle; else above = middle;
        }
        double saturation = 0.5 * (below + above);
        // PR78's nitrogen saturation pressure at 77.355 K is +1.23 % against the 101.325 kPa of its normal boiling point (package evidence).
        assertEquals(101_325.0 * 1.0123, saturation, 0.002 * 101_325.0);

        for (double p : new double[] {90_000.0, 95_000.0, 100_000.0, saturation * (1.0 - 1.0e-9)}) {
            Result result = test.test(t, p, pure);
            assertEquals(Verdict.STABLE, result.verdict(), "p = " + p);
            assertEquals(Root.VAPOR, result.feedRoot(), "p = " + p);
        }
        for (double p : new double[] {saturation * (1.0 + 1.0e-9), 105_000.0, 110_000.0, 120_000.0}) {
            Result result = test.test(t, p, pure);
            assertEquals(Verdict.STABLE, result.verdict(), "p = " + p);
            assertEquals(Root.LIQUID, result.feedRoot(), "p = " + p);
        }
        // At the equal-fugacity pressure the other root's distance vanishes: pure coexistence, not a phase split.
        Result nearSaturation = test.test(t, saturation * (1.0 + 1.0e-9), pure);
        assertTrue(Math.abs(nearSaturation.minimumTangentPlaneDistance()) < 1.0e-8);
    }

    // (b) Methane/nitrogen against a brute-force tangent-plane minimum.

    @Test
    void methaneNitrogenVerdictsMatchABruteForceTangentPlaneMinimum() {
        var kernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen");
        var test = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT);
        var workspace = test.newWorkspace();
        int states = 0;
        int unstable = 0;
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 1.0e6, 1.5e6, 2.0e6, 2.5e6, 3.0e6}) {
                for (double nitrogen : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
                    double[] z = {1.0 - nitrogen, nitrogen};
                    Result result = test.test(t, p, z, workspace);
                    double minimum = bruteForceMinimum(kernel, t, p, z);
                    String state = t + " K, " + p + " Pa, x_N2 " + nitrogen;
                    boolean bruteUnstable = minimum < -EPSILON;
                    assertEquals(bruteUnstable ? Verdict.UNSTABLE : Verdict.STABLE, result.verdict(), state);
                    if (bruteUnstable) {
                        unstable++;
                        // The trial found the global minimum: tm at a stationary point is 1 - exp(-D).
                        assertEquals(-Math.expm1(-minimum), result.minimumTangentPlaneDistance(), 1.0e-9, state);
                        assertStationary(kernel, t, p, z, result, state);
                    } else {
                        assertTrue(result.minimumTangentPlaneDistance() >= -EPSILON, state);
                    }
                    states++;
                }
            }
        }
        assertEquals(60, states);
        assertTrue(unstable >= 10 && states - unstable >= 10, "both verdicts are exercised: " + unstable);
    }

    // (c) Supercritical and dense single-phase states.

    @Test
    void supercriticalAndDenseSinglePhaseStatesAreStable() {
        var nitrogen = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        var methane = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane"), TangentPlaneStability.Settings.DEFAULT);
        assertEquals(Verdict.STABLE, nitrogen.test(130.0, 6.0e6, new double[] {1.0}).verdict());
        assertEquals(Verdict.STABLE, nitrogen.test(150.0, 10.0e6, new double[] {1.0}).verdict());
        assertEquals(Verdict.STABLE, methane.test(250.0, 10.0e6, new double[] {1.0}).verdict());

        // The same states for equimolar methane/nitrogen run the full trial machinery; brute force agrees.
        var binaryKernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen");
        var binary = new TangentPlaneStability(binaryKernel, TangentPlaneStability.Settings.DEFAULT);
        double[] z = {0.5, 0.5};
        for (double[] state : new double[][] {{130.0, 6.0e6}, {150.0, 10.0e6}, {250.0, 10.0e6}}) {
            Result result = binary.test(state[0], state[1], z);
            assertEquals(Verdict.STABLE, result.verdict(), Arrays.toString(state));
            assertTrue(result.trials() == 4 && result.iterations() > 0, Arrays.toString(state));
            assertTrue(bruteForceMinimum(binaryKernel, state[0], state[1], z) >= -EPSILON, Arrays.toString(state));
        }
    }

    // (d) The network package against the network flash.

    /**
     * The network basis is the package's 20 PR components plus water, which the network models as a separate
     * free-water phase outside the equation of state; the stability test runs on the 20 EOS components, all
     * present. The reference is {@link FluidThermodynamics#flashTP} with no water: its hydrocarbon split is a
     * Wilson-started Rachford-Rice successive substitution whose phase count is the number of phases it returns
     * with material (the vapour on the state pressure, the liquid through the network's 2 MPa reference and
     * compressibility response, so its liquid is not the kernel's liquid at the state pressure).
     */
    @Test
    void networkPackageVerdictsMatchTheNetworkFlashPhaseCount() {
        var kernel = kernel(PengRobinsonKernel.Mixing.SPARSE_PAIRS);
        var test = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT);
        // The network model on the kernel's compounds, as an island holding only them runs (batch 2026-11-04-refinery-compounds, V2).
        var fluid = FluidTestSupport.networkModel().on(MaterialCatalog.bundled().requirePackage(NETWORK).components());
        double[] z = crudeWithNitrogen();
        int n = kernel.componentCount();
        assertEquals(20, n);
        assertEquals(n + 1, fluid.componentCount());
        for (double amount : z) assertTrue(amount > 0.0);

        double[][] states = {{350.0, 0.5e6}, {600.0, 2.0e6}, {900.0, 0.1e6}};
        Verdict[] expected = {Verdict.UNSTABLE, Verdict.UNSTABLE, Verdict.STABLE};
        for (int s = 0; s < states.length; s++) {
            double t = states[s][0];
            double p = states[s][1];
            Result result = test.test(t, p, z);
            var flash = fluid.flashTP(t, p, Arrays.copyOf(z, n + 1), () -> { });
            int phases = (sum(flash.liquidView()) > 0.0 ? 1 : 0) + (sum(flash.vaporView()) > 0.0 ? 1 : 0);
            String state = t + " K, " + p + " Pa";
            assertEquals(expected[s], result.verdict(), state);
            assertEquals(phases == 2 ? Verdict.UNSTABLE : Verdict.STABLE, result.verdict(), state + ", flash phases " + phases);
        }
    }

    // (e) Determinism.

    @Test
    void repeatedCallsAreBitwiseIdentical() {
        var binaryKernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen");
        var networkKernel = kernel(PengRobinsonKernel.Mixing.SPARSE_PAIRS);
        var nitrogenKernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Nitrogen");
        record Case(TangentPlaneStability test, double t, double p, double[] z) {}
        var binary = new TangentPlaneStability(binaryKernel, TangentPlaneStability.Settings.DEFAULT);
        var network = new TangentPlaneStability(networkKernel, TangentPlaneStability.Settings.DEFAULT);
        List<Case> cases = List.of(
                new Case(binary, 110.0, 0.5e6, new double[] {0.5, 0.5}),
                new Case(binary, 120.0, 3.0e6, new double[] {0.3, 0.7}),
                new Case(binary, 145.0, 4.46e6, new double[] {0.25, 0.75}),
                new Case(network, 350.0, 0.5e6, crudeWithNitrogen()),
                new Case(network, 900.0, 0.1e6, crudeWithNitrogen()),
                new Case(new TangentPlaneStability(nitrogenKernel, TangentPlaneStability.Settings.DEFAULT), 77.355, 90_000.0, new double[] {1.0}));
        List<Result> first = new ArrayList<>();
        for (Case c : cases) first.add(c.test().test(c.t(), c.p(), c.z()));
        // A workspace reused across all cases in reverse order, then in order, must not carry anything over.
        var reused = new java.util.IdentityHashMap<TangentPlaneStability, TangentPlaneStability.Workspace>();
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < cases.size(); k++) {
                int index = pass == 0 ? cases.size() - 1 - k : k;
                Case c = cases.get(index);
                var workspace = reused.computeIfAbsent(c.test(), TangentPlaneStability::newWorkspace);
                assertBitwiseEqual(first.get(index), c.test().test(c.t(), c.p(), c.z(), workspace));
            }
        }
        // Mole numbers and their fractions describe the same feed.
        Case scaled = cases.get(0);
        assertBitwiseEqual(first.get(0), scaled.test().test(scaled.t(), scaled.p(), new double[] {3.0, 3.0}));
    }

    // (f) Refusal.

    @Test
    void refusesACompositionWithANegativeOrNoAmount() {
        var test = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, 1.0e6, new double[] {1.0, -1.0e-12}));
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, 1.0e6, new double[] {0.0, 0.0}));
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, 1.0e6, new double[] {Double.NaN, 1.0}));
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, 1.0e6, new double[] {Double.POSITIVE_INFINITY, 1.0}));
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, 1.0e6, new double[] {1.0}));
        assertThrows(NullPointerException.class, () -> test.test(120.0, 1.0e6, null));
        assertThrows(IllegalArgumentException.class, () -> test.test(120.0, -1.0, new double[] {0.5, 0.5}));
    }

    /** A component the feed lacks takes no part: the verdict is the one of the reduced feed. */
    @Test
    void absentComponentsStayAbsentFromEveryTrial() {
        var three = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Ethane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        var two = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        Result full = three.test(110.0, 0.5e6, new double[] {0.5, 0.0, 0.5});
        Result reduced = two.test(110.0, 0.5e6, new double[] {0.5, 0.5});
        assertEquals(reduced.verdict(), full.verdict());
        assertEquals(0.0, full.trialComposition()[1]);
        assertEquals(reduced.minimumTangentPlaneDistance(), full.minimumTangentPlaneDistance(), 1.0e-12);
        // One present component is a pure fluid, whatever the basis.
        assertEquals(Verdict.STABLE, three.test(110.0, 0.5e6, new double[] {0.0, 0.0, 1.0}).verdict());
    }

    // (g) Per-branch pressures (P3, the free-water rule's hydrocarbon vapour at its partial pressure).

    /** With the vapour pressure equal to the liquid's, the per-branch call is the one-pressure test, bit for bit. */
    @Test
    void equalBranchPressuresAreTheOnePressureTestBitwise() {
        var binary = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        var network = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.SPARSE_PAIRS), TangentPlaneStability.Settings.DEFAULT);
        var nitrogen = new TangentPlaneStability(kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Nitrogen"), TangentPlaneStability.Settings.DEFAULT);
        record Case(TangentPlaneStability test, double t, double p, double[] z) {}
        List<Case> cases = List.of(
                new Case(binary, 110.0, 0.5e6, new double[] {0.5, 0.5}),
                new Case(binary, 120.0, 3.0e6, new double[] {0.3, 0.7}),
                new Case(binary, 145.0, 4.46e6, new double[] {0.25, 0.75}),
                new Case(network, 350.0, 0.5e6, crudeWithNitrogen()),
                new Case(network, 900.0, 0.1e6, crudeWithNitrogen()),
                new Case(nitrogen, 77.355, 90_000.0, new double[] {1.0}));
        for (Case c : cases) {
            int n = c.test().kernel().componentCount();
            double[] translations = new double[n];
            Arrays.fill(translations, -3.5e-6);
            Result one = c.test().test(c.t(), c.p(), c.z(), c.test().newWorkspace());
            assertBitwiseEqual(one, c.test().test(c.t(), c.p(), c.p(), c.z(), c.test().newWorkspace()));
            assertBitwiseEqual(one, c.test().test(c.t(), c.p(), c.p(), translations, c.z(), c.test().newWorkspace()));
            assertEquals(Double.doubleToRawLongBits(one.nearestStationaryDistance()), Double.doubleToRawLongBits(
                    c.test().test(c.t(), c.p(), c.p(), translations, c.z(), c.test().newWorkspace()).nearestStationaryDistance()));
        }
    }

    /**
     * Methane/nitrogen with the vapour branch at 0.8 of the liquid's pressure and P1-sized translations: the verdict
     * agrees with a brute-force minimum of the per-branch tangent-plane distance
     * {@code sum_i w_i (ln w_i + ln phi_i + s_i - d_i)}, the vapour branch shifted by
     * {@code s_i = ln(p_V/p_L) + (p_V - p_L) c_i/(R T)} and admitted only as three roots' largest or a vapour-like single
     * root.
     */
    @Test
    void perBranchVerdictsMatchABruteForceMinimum() {
        var kernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen");
        var test = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT);
        var workspace = test.newWorkspace();
        double[] translations = {-3.389e-6, -3.519e-6};
        int states = 0;
        int unstable = 0;
        for (double t : new double[] {110.0, 120.0}) {
            for (double p : new double[] {0.5e6, 1.0e6, 1.5e6, 2.0e6, 2.5e6, 3.0e6}) {
                for (double nitrogen : new double[] {0.1, 0.3, 0.5, 0.7, 0.9}) {
                    double[] z = {1.0 - nitrogen, nitrogen};
                    double vapour = 0.8 * p;
                    Result result = test.test(t, p, vapour, translations, z, workspace);
                    double minimum = bruteForceBranchMinimum(kernel, t, p, vapour, translations, z);
                    String state = t + " K, " + p + " Pa (vapour " + vapour + " Pa), x_N2 " + nitrogen;
                    boolean bruteUnstable = minimum < -EPSILON;
                    assertEquals(bruteUnstable ? Verdict.UNSTABLE : Verdict.STABLE, result.verdict(), state + ", minimum " + minimum);
                    if (bruteUnstable) unstable++;
                    states++;
                }
            }
        }
        assertEquals(60, states);
        assertTrue(unstable >= 10 && states - unstable >= 10, "both verdicts are exercised: " + unstable);
    }

    /**
     * A dense single-root liquid is not unstable against itself at the lower vapour pressure (its single root there is
     * liquid-like, so it is no vapour branch), while a light gas takes the vapour branch at the lower pressure.
     */
    @Test
    void aDenseLiquidIsNoVapourAtTheLowerPressure() {
        var kernel = kernel(PengRobinsonKernel.Mixing.CLASSICAL, "Methane", "Nitrogen");
        var test = new TangentPlaneStability(kernel, TangentPlaneStability.Settings.DEFAULT);
        double[] z = {0.5, 0.5};
        Result dense = test.test(120.0, 3.0e6, 2.9e6, z, test.newWorkspace());
        assertEquals(Verdict.STABLE, dense.verdict());
        assertEquals(Root.LIQUID, dense.feedRoot());
        // Without the phase identification the same root at the lower pressure would have the lower fugacity.
        var workspace = kernel.newWorkspace();
        var atLiquid = kernel.newEvaluation();
        var atVapour = kernel.newEvaluation();
        kernel.evaluate(120.0, 3.0e6, z, Root.LIQUID, workspace, atLiquid);
        kernel.evaluate(120.0, 2.9e6, z, Root.VAPOR, workspace, atVapour);
        assertEquals(1, atVapour.physicalRootCount());
        double shift = Math.log(2.9 / 3.0);
        assertTrue(0.5 * (atVapour.logFugacityCoefficient(0) + atVapour.logFugacityCoefficient(1)) + shift
                < 0.5 * (atLiquid.logFugacityCoefficient(0) + atLiquid.logFugacityCoefficient(1)));
        assertTrue(com.wormzjl.icheme.science.thermo.phase.PhaseIdentification.liquidLike(
                com.wormzjl.icheme.science.thermo.phase.PhaseIdentification.parameter(atVapour, 120.0, 2.9e6)));
        Result gas = test.test(400.0, 3.0e6, 2.9e6, z, test.newWorkspace());
        assertEquals(Verdict.STABLE, gas.verdict());
        assertEquals(Root.VAPOR, gas.feedRoot());
    }

    /** {@link #bruteForceMinimum} with per-branch pressures and shifts, and the vapour branch's admission rule. */
    static double bruteForceBranchMinimum(PengRobinsonKernel kernel, double t, double liquidPressure, double vaporPressure,
                                          double[] translations, double[] z) {
        var workspace = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        double[] shift = new double[2];
        for (int i = 0; i < 2; i++) {
            shift[i] = Math.log(vaporPressure / liquidPressure)
                    + (vaporPressure - liquidPressure) * translations[i] / (PengRobinsonKernel.GAS_CONSTANT * t);
        }
        // Lowest per-branch value of sum_i x_i (ln phi_i + s_i) and its ln phi_i + s_i, over the admissible branches.
        java.util.function.BiFunction<double[], double[], Double> lowest = (x, logPhi) -> {
            double best = Double.POSITIVE_INFINITY;
            for (Root root : Root.values()) {
                double pressure = root == Root.VAPOR ? vaporPressure : liquidPressure;
                kernel.evaluate(t, pressure, x, root, workspace, evaluation);
                if (root == Root.VAPOR && evaluation.physicalRootCount() == 1
                        && !com.wormzjl.icheme.science.thermo.phase.PhaseIdentification.vapourLike(
                                com.wormzjl.icheme.science.thermo.phase.PhaseIdentification.parameter(evaluation, t, pressure))) {
                    continue;
                }
                double value = 0.0;
                for (int i = 0; i < 2; i++) {
                    double s = root == Root.VAPOR ? shift[i] : 0.0;
                    if (x[i] > 0.0) value += x[i] * (evaluation.logFugacityCoefficient(i) + s);
                }
                if (value < best) {
                    best = value;
                    for (int i = 0; i < 2; i++) logPhi[i] = evaluation.logFugacityCoefficient(i) + (root == Root.VAPOR ? shift[i] : 0.0);
                }
            }
            return best;
        };
        double[] feedLogPhi = new double[2];
        lowest.apply(z, feedLogPhi);
        double[] reference = {Math.log(z[0]) + feedLogPhi[0], Math.log(z[1]) + feedLogPhi[1]};
        double minimum = Double.POSITIVE_INFINITY;
        double[] trial = new double[2];
        double[] trialLogPhi = new double[2];
        for (int k = 1; k < 4000; k++) {
            trial[0] = 1.0 - k / 4000.0;
            trial[1] = k / 4000.0;
            lowest.apply(trial, trialLogPhi);
            double value = 0.0;
            for (int i = 0; i < 2; i++) value += trial[i] * (Math.log(trial[i]) + trialLogPhi[i] - reference[i]);
            minimum = Math.min(minimum, value);
        }
        return minimum;
    }

    /**
     * {@code min_w D(w)} over both roots for a binary: a grid of 3999 interior mole fractions plus logarithmic
     * ends down to 1e-10, each discrete local minimum refined by golden section.
     */
    static double bruteForceMinimum(PengRobinsonKernel kernel, double t, double p, double[] z) {
        var workspace = kernel.newWorkspace();
        var evaluation = kernel.newEvaluation();
        double[] feedLogPhi = null;
        double feedGibbs = Double.POSITIVE_INFINITY;
        for (Root root : Root.values()) {
            kernel.evaluate(t, p, z, root, workspace, evaluation);
            double gibbs = z[0] * evaluation.logFugacityCoefficient(0) + z[1] * evaluation.logFugacityCoefficient(1);
            if (gibbs < feedGibbs) {
                feedGibbs = gibbs;
                feedLogPhi = evaluation.logFugacityCoefficients();
            }
        }
        double[] reference = {Math.log(z[0]) + feedLogPhi[0], Math.log(z[1]) + feedLogPhi[1]};
        double[] trial = new double[2];
        DoubleUnaryOperator distance = x -> {
            trial[0] = 1.0 - x;
            trial[1] = x;
            double lowest = Double.POSITIVE_INFINITY;
            for (Root root : Root.values()) {
                kernel.evaluate(t, p, trial, root, workspace, evaluation);
                double value = 0.0;
                for (int i = 0; i < 2; i++) {
                    value += trial[i] * (Math.log(trial[i]) + evaluation.logFugacityCoefficient(i) - reference[i]);
                }
                lowest = Math.min(lowest, value);
            }
            return lowest;
        };
        List<Double> points = new ArrayList<>();
        for (int i = 1; i < 4000; i++) points.add(i / 4000.0);
        for (int e = -100; e < -20; e++) {
            double small = Math.pow(10.0, e / 10.0);
            points.add(small);
            points.add(1.0 - small);
        }
        double[] x = points.stream().mapToDouble(Double::doubleValue).sorted().distinct().toArray();
        double[] f = new double[x.length];
        for (int i = 0; i < x.length; i++) f[i] = distance.applyAsDouble(x[i]);
        double minimum = Double.POSITIVE_INFINITY;
        double golden = (Math.sqrt(5.0) - 1.0) / 2.0;
        for (int i = 0; i < x.length; i++) {
            minimum = Math.min(minimum, f[i]);
            if (i == 0 || i == x.length - 1 || f[i] > f[i - 1] || f[i] > f[i + 1]) continue;
            double a = x[i - 1];
            double b = x[i + 1];
            double c = b - golden * (b - a);
            double d = a + golden * (b - a);
            double fc = distance.applyAsDouble(c);
            double fd = distance.applyAsDouble(d);
            for (int iteration = 0; iteration < 80; iteration++) {
                if (fc < fd) {
                    b = d; d = c; fd = fc; c = b - golden * (b - a); fc = distance.applyAsDouble(c);
                } else {
                    a = c; c = d; fc = fd; d = a + golden * (b - a); fd = distance.applyAsDouble(d);
                }
            }
            minimum = Math.min(minimum, Math.min(fc, fd));
        }
        return minimum;
    }

    /** At a stationary point {@code ln w_i + ln phi_i(w) - d_i} is the same for every i, and equals {@code -ln S}. */
    private static void assertStationary(PengRobinsonKernel kernel, double t, double p, double[] z, Result result, String state) {
        var workspace = kernel.newWorkspace();
        var feed = kernel.newEvaluation();
        var trial = kernel.newEvaluation();
        kernel.evaluate(t, p, z, result.feedRoot(), workspace, feed);
        double[] w = result.trialComposition();
        kernel.evaluate(t, p, w, result.trialRoot(), workspace, trial);
        double shift0 = Math.log(w[0]) + trial.logFugacityCoefficient(0) - Math.log(z[0]) - feed.logFugacityCoefficient(0);
        double shift1 = Math.log(w[1]) + trial.logFugacityCoefficient(1) - Math.log(z[1]) - feed.logFugacityCoefficient(1);
        assertEquals(shift0, shift1, 1.0e-9, state);
        assertEquals(result.minimumTangentPlaneDistance(), 1.0 - Math.exp(-shift0), 1.0e-9, state);
    }

    private static void assertBitwiseEqual(Result expected, Result actual) {
        assertEquals(expected.verdict(), actual.verdict());
        assertEquals(Double.doubleToRawLongBits(expected.minimumTangentPlaneDistance()),
                Double.doubleToRawLongBits(actual.minimumTangentPlaneDistance()));
        double[] a = expected.trialComposition();
        double[] b = actual.trialComposition();
        long[] aBits = new long[a.length];
        long[] bBits = new long[b.length];
        for (int i = 0; i < a.length; i++) aBits[i] = Double.doubleToRawLongBits(a[i]);
        for (int i = 0; i < b.length; i++) bBits[i] = Double.doubleToRawLongBits(b[i]);
        assertArrayEquals(aBits, bBits);
        assertEquals(expected.trialRoot(), actual.trialRoot());
        assertEquals(expected.feedRoot(), actual.feedRoot());
        assertEquals(expected.trials(), actual.trials());
        assertEquals(expected.iterations(), actual.iterations());
        assertEquals(expected.kernelEvaluations(), actual.kernelEvaluations());
        assertEquals(expected.derivativeEvaluations(), actual.derivativeEvaluations());
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}

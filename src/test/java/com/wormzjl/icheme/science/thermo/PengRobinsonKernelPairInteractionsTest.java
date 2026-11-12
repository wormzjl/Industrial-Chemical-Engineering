package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The kernel on a {@link PairInteractions} model: on a constant model it is
 * the constant matrix's kernel bit for bit; on the E-PPR78 pilot model every temperature derivative
 * the kernel forms (including the interactions' own {@code dk/dT} and {@code d^2k/dT^2} terms) matches central
 * differences of the values it differentiates; a reused workspace equals fresh ones bit for bit; and a pair value that
 * is not finite is refused without being cached.
 */
class PengRobinsonKernelPairInteractionsTest {
    private static final double TEMPERATURE_STEP = 1.0e-3;
    private static final double MOLE_STEP = 1.0e-6;
    private static final double TOLERANCE = 1.0e-6;

    // ---------------------------------------------------------------------------------------------------------------
    // Constant reduction
    // ---------------------------------------------------------------------------------------------------------------

    @Test void constantModelReproducesSparsePairsBitForBit() {
        int compared = 0;
        // The pilot species with typical constant PR k_ij (CH4/C2H6 left zero, so a zero entry sits among the pairs).
        double[][] pilot = new double[4][4];
        set(pilot, PairInteractionsTest.CH4, PairInteractionsTest.N2, 0.03);
        set(pilot, PairInteractionsTest.CH4, PairInteractionsTest.CO2, 0.10);
        set(pilot, PairInteractionsTest.C2H6, PairInteractionsTest.CO2, 0.13);
        set(pilot, PairInteractionsTest.CO2, PairInteractionsTest.N2, -0.02);
        set(pilot, PairInteractionsTest.C2H6, PairInteractionsTest.N2, 0.05);
        compared += compareConstant(PairInteractionsTest.TC, PairInteractionsTest.PC, PairInteractionsTest.OMEGA, pilot,
                pilotStates(), 11);
        // Twenty synthetic species (both kappa branches) with fifteen nonzero pairs, like a network package.
        var random = new Random(20260924L);
        int n = 20;
        double[] tc = new double[n];
        double[] pc = new double[n];
        double[] omega = new double[n];
        for (int i = 0; i < n; i++) {
            tc[i] = 120.0 + 580.0 * random.nextDouble();
            pc[i] = 1.5e6 + 5.5e6 * random.nextDouble();
            omega[i] = 0.7 * random.nextDouble();
        }
        double[][] sparse = new double[n][n];
        for (int placed = 0; placed < 15; ) {
            int i = random.nextInt(n);
            int j = random.nextInt(n);
            if (i == j || sparse[i][j] != 0.0) continue;
            set(sparse, i, j, 0.2 * random.nextDouble() - 0.05);
            placed++;
        }
        var wide = new ArrayList<State>();
        for (double[] point : new double[][] {{250, 1e5}, {320, 5e5}, {400, 2e6}, {500, 5e6}, {650, 1e6}, {800, 1e7}}) {
            double[] x = new double[n];
            for (int i = 0; i < n; i++) x[i] = Math.pow(10.0, -2.0 * random.nextDouble());
            wide.add(new State(point[0], point[1], x));
        }
        compared += compareConstant(tc, pc, omega, sparse, wide, 12);
        // No interaction at all: SPARSE_PAIRS falls to its rank-one composition derivative, the model has no pairs.
        compared += compareConstant(tc, pc, omega, new double[n][n], wide, 13);
        System.out.println("TEMPERATURE_DEPENDENT_PAIRS on constant models vs SPARSE_PAIRS: " + compared
                + " doubles compared, all bit-identical");
        assertTrue(compared > 10_000, "too few comparisons: " + compared);
    }

    private static int compareConstant(double[] tc, double[] pc, double[] omega, double[][] matrix, List<State> states, long seed) {
        var sparse = new PengRobinsonKernel(tc, pc, omega, matrix, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE);
        var dependent = new PengRobinsonKernel(tc, pc, omega, PairInteractionsTest.constant(matrix),
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
        assertEquals(sparse.usesRankOneMixing(), dependent.usesRankOneMixing());
        int n = tc.length;
        var sparseWorkspace = sparse.newWorkspace();
        var dependentWorkspace = dependent.newWorkspace();
        var sparseDerivatives = sparse.newDerivatives();
        var dependentDerivatives = dependent.newDerivatives();
        double[][] scratch = new double[6][n];
        int compared = 0;
        for (State state : states) {
            for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                RuntimeException sparseFailure = null;
                RuntimeException dependentFailure = null;
                try {
                    sparse.evaluateDerivatives(state.t, state.p, state.x, root, sparseWorkspace, sparseDerivatives);
                } catch (RuntimeException failure) { sparseFailure = failure; }
                try {
                    dependent.evaluateDerivatives(state.t, state.p, state.x, root, dependentWorkspace, dependentDerivatives);
                } catch (RuntimeException failure) { dependentFailure = failure; }
                assertEquals(sparseFailure == null, dependentFailure == null, "same refusal at " + state);
                if (sparseFailure != null) continue;
                var e = sparseDerivatives.evaluation();
                var f = dependentDerivatives.evaluation();
                String at = state + " " + root;
                compared += same(e.logFugacityCoefficientsView(), f.logFugacityCoefficientsView(), at + " ln phi");
                compared += same(e.compressibility(), f.compressibility(), at + " Z");
                compared += same(e.residualEnthalpyJoulesPerMol(), f.residualEnthalpyJoulesPerMol(), at + " h^R");
                compared += same(e.aMix(), f.aMix(), at + " a");
                compared += same(e.bMix(), f.bMix(), at + " b");
                compared += same(e.daMixDt(), f.daMixDt(), at + " da/dT");
                assertEquals(e.physicalRootCount(), f.physicalRootCount());
                compared += same(e.rootSeparation(), f.rootSeparation(), at + " separation");
                compared += same(sparseDerivatives.dLogPhiDtView(), dependentDerivatives.dLogPhiDtView(), at + " d ln phi/dT");
                compared += same(sparseDerivatives.partialMolarResidualEnthalpyView(),
                        dependentDerivatives.partialMolarResidualEnthalpyView(), at + " h^R_i");
                compared += same(sparseDerivatives.dResidualEnthalpyDt(), dependentDerivatives.dResidualEnthalpyDt(), at + " dh^R/dT");
                for (int i = 0; i < n; i++) {
                    compared += same(sparseDerivatives.dLogPhiDnRowView(i), dependentDerivatives.dLogPhiDnRowView(i), at + " d ln phi/dn");
                }
                double sparseSecond = sparse.secondTemperatureDerivative(state.t, sparseWorkspace, scratch[0], scratch[1], scratch[2]);
                double dependentSecond = dependent.secondTemperatureDerivative(state.t, dependentWorkspace, scratch[3], scratch[4], scratch[5]);
                compared += same(sparseSecond, dependentSecond, at + " d2a/dT2");
                for (int s = 0; s < 3; s++) compared += same(scratch[s], scratch[s + 3], at + " root derivative scratch " + s);
                // The plain value path too (a different entry point into the same arithmetic).
                sparse.evaluate(state.t, state.p, state.x, root, sparseWorkspace, e);
                dependent.evaluate(state.t, state.p, state.x, root, dependentWorkspace, f);
                compared += same(e.logFugacityCoefficientsView(), f.logFugacityCoefficientsView(), at + " evaluate ln phi");
                compared += same(e.daMixDt(), f.daMixDt(), at + " evaluate da/dT");
            }
        }
        assertTrue(compared > 0, "seed " + seed + " compared nothing");
        return compared;
    }

    private static int same(double expected, double actual, String what) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
                () -> what + ": " + expected + " vs " + actual);
        return 1;
    }

    private static int same(double[] expected, double[] actual, String what) {
        for (int i = 0; i < expected.length; i++) same(expected[i], actual[i], what + "[" + i + "]");
        return expected.length;
    }

    private static void set(double[][] matrix, int i, int j, double value) {
        matrix[i][j] = value;
        matrix[j][i] = value;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // E-PPR78 derivatives
    // ---------------------------------------------------------------------------------------------------------------

    /** Twenty pilot states: twelve four-component, eight binary (the other two amounts exactly zero). */
    static List<State> pilotStates() {
        var states = new ArrayList<State>();
        var random = new Random(20260924L);
        for (double[] point : new double[][] {{95, 1e5}, {110, 5e5}, {130, 2e6}, {150, 1e6}, {170, 4e6}, {190, 2e6},
                {220, 6e6}, {250, 1e6}, {300, 1e7}, {450, 2e6}, {900, 2e5}, {1150, 2e5}}) {
            double[] x = new double[4];
            for (int i = 0; i < 4; i++) x[i] = Math.pow(10.0, -2.0 * random.nextDouble());
            states.add(new State(point[0], point[1], x));
        }
        int n2 = PairInteractionsTest.N2, ch4 = PairInteractionsTest.CH4, c2 = PairInteractionsTest.C2H6, co2 = PairInteractionsTest.CO2;
        states.add(binary(100, 3e5, n2, ch4, 0.3));
        states.add(binary(160, 3e6, n2, ch4, 0.2));
        states.add(binary(200, 2e6, ch4, c2, 0.5));
        states.add(binary(240, 5e6, ch4, co2, 0.6));
        states.add(binary(260, 3e6, c2, co2, 0.4));
        states.add(binary(230, 8e6, co2, n2, 0.7));
        states.add(binary(180, 1e6, c2, n2, 0.8));
        states.add(binary(600, 5e6, ch4, c2, 0.5));
        return List.copyOf(states);
    }

    private static State binary(double t, double p, int first, int second, double fraction) {
        double[] x = new double[4];
        x[first] = fraction;
        x[second] = 1.0 - fraction;
        return new State(t, p, x);
    }

    @Test void groupModelDerivativesMatchCentralDifferences() {
        var kernel = PairInteractionsTest.pilotKernel(PairInteractionsTest.PC);
        var workspace = kernel.newWorkspace();
        var derivatives = kernel.newDerivatives();
        var probe = kernel.newEvaluation();
        int n = 4;
        double[] rootDt = new double[n], rootDt2 = new double[n], crossDt = new double[n];
        var worst = new LinkedHashMap<String, Worst>();
        int evaluations = 0;
        double largestInteractionShare = 0.0;
        for (State state : pilotStates()) {
            for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                try {
                    kernel.evaluateDerivatives(state.t, state.p, state.x, root, workspace, derivatives);
                } catch (RuntimeException unavailable) { continue; }
                evaluations++;
                String at = state + " " + root;
                var base = derivatives.evaluation();
                double a = base.aMix();
                double daDt = base.daMixDt();
                double z = base.compressibility();
                double hR = base.residualEnthalpyJoulesPerMol();
                double[] dLogPhiDt = derivatives.dLogPhiDtView().clone();
                double[] partial = derivatives.partialMolarResidualEnthalpyView().clone();
                double dHrDt = derivatives.dResidualEnthalpyDt();
                double[][] dLogPhiDn = new double[n][];
                for (int i = 0; i < n; i++) dLogPhiDn[i] = derivatives.dLogPhiDnRowView(i).clone();
                kernel.evaluate(state.t, state.p, state.x, root, workspace, probe);
                double secondDaDt = kernel.secondTemperatureDerivative(state.t, workspace, rootDt, rootDt2, crossDt);

                // How much of da/dT the interactions' own temperature derivative carries: the check below has teeth
                // only if dropping that term would be visible.
                double interactionDt = 0.0;
                var model = PairInteractionsTest.pilotModel();
                double[] roots = workspace.attractionRootsView();
                double[] kDt = PairInteractionsTest.kernelValues(kernel, model, kernel.newWorkspace(), state.t, 1);
                double[] composition = workspace.compositionView();
                for (int pair = 0; pair < model.pairCount(); pair++) {
                    int i = model.first(pair), j = model.second(pair);
                    interactionDt += 2.0 * kDt[pair] * composition[i] * roots[i] * composition[j] * roots[j];
                }
                largestInteractionShare = Math.max(largestInteractionShare, Math.abs(interactionDt / daDt));

                double partialSum = 0.0;
                for (int i = 0; i < n; i++) partialSum += state.fractions[i] * partial[i];
                record(worst, "sum x_i h^R_i = h^R", at, hR, partialSum, Math.abs(hR));

                if (keepsRoot(kernel, state, root, z, TEMPERATURE_STEP, -1)) {
                    var higher = evaluate(kernel, state.t + TEMPERATURE_STEP, state.p, state.x, root);
                    var lower = evaluate(kernel, state.t - TEMPERATURE_STEP, state.p, state.x, root);
                    record(worst, "da_mix/dT", at, (higher.aMix() - lower.aMix()) / (2 * TEMPERATURE_STEP), daDt, Math.abs(daDt));
                    record(worst, "d2a_mix/dT2", at, (higher.daMixDt() - lower.daMixDt()) / (2 * TEMPERATURE_STEP),
                            secondDaDt, Math.abs(secondDaDt));
                    record(worst, "dh^R/dT", at, (higher.residualEnthalpyJoulesPerMol() - lower.residualEnthalpyJoulesPerMol())
                            / (2 * TEMPERATURE_STEP), dHrDt, Math.abs(dHrDt));
                    double scale = 0.0;
                    for (double value : dLogPhiDt) scale = Math.max(scale, Math.abs(value));
                    for (int i = 0; i < n; i++) {
                        record(worst, "d ln phi_i/dT", at, (higher.logFugacityCoefficient(i) - lower.logFugacityCoefficient(i))
                                / (2 * TEMPERATURE_STEP), dLogPhiDt[i], scale);
                    }
                }
                double scale = 0.0;
                for (double[] row : dLogPhiDn) for (double value : row) scale = Math.max(scale, Math.abs(value));
                for (int j = 0; j < n; j++) {
                    if (!(state.fractions[j] > 10 * MOLE_STEP) || !keepsRoot(kernel, state, root, z, 0.0, j)) continue;
                    double[] up = state.fractions.clone();
                    double[] down = state.fractions.clone();
                    up[j] += MOLE_STEP;
                    down[j] -= MOLE_STEP;
                    var higher = evaluate(kernel, state.t, state.p, up, root);
                    var lower = evaluate(kernel, state.t, state.p, down, root);
                    for (int i = 0; i < n; i++) {
                        record(worst, "d ln phi_i/dn_j", at, (higher.logFugacityCoefficient(i) - lower.logFugacityCoefficient(i))
                                / (2 * MOLE_STEP), dLogPhiDn[i][j], scale);
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "E-PPR78 kernel derivatives vs central differences: %d phase evaluations;"
                + " interaction term up to %.1f %% of da_mix/dT%n", evaluations, 100 * largestInteractionShare);
        worst.forEach((quantity, value) -> System.out.println("    " + String.format(Locale.ROOT, "%-22s", quantity) + value));
        assertTrue(evaluations >= 30, "too few evaluations: " + evaluations);
        assertTrue(largestInteractionShare > 1.0e-2, "the pilot states do not exercise dk/dT: " + largestInteractionShare);
        for (String quantity : List.of("da_mix/dT", "d2a_mix/dT2", "dh^R/dT", "d ln phi_i/dT", "d ln phi_i/dn_j")) {
            assertTrue(worst.containsKey(quantity) && worst.get(quantity).count >= 20, quantity + " barely compared");
        }
        worst.forEach((quantity, value) -> assertTrue(value.deviation <= TOLERANCE, quantity + ": " + value));
    }

    private static PengRobinsonKernel.Evaluation evaluate(PengRobinsonKernel kernel, double t, double p, double[] x,
                                                           PengRobinsonKernel.Root root) {
        var output = kernel.newEvaluation();
        kernel.evaluate(t, p, x, root, kernel.newWorkspace(), output);
        return output;
    }

    /** Both probe endpoints stay on the root the base state is on (a derivative is a derivative of one branch). */
    private static boolean keepsRoot(PengRobinsonKernel kernel, State state, PengRobinsonKernel.Root root, double z,
                                     double temperatureStep, int component) {
        try {
            for (int sign = -1; sign <= 1; sign += 2) {
                double[] x = state.fractions.clone();
                if (component >= 0) x[component] += sign * MOLE_STEP;
                var probe = evaluate(kernel, state.t + sign * temperatureStep, state.p, x, root);
                if (Math.abs(probe.compressibility() - z) > 1e-3 * Math.abs(z)) return false;
            }
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Determinism and refusals
    // ---------------------------------------------------------------------------------------------------------------

    @Test void reusedWorkspaceMatchesFreshWorkspacesBitForBit() {
        var kernel = PairInteractionsTest.pilotKernel(PairInteractionsTest.PC);
        var reused = kernel.newWorkspace();
        var reusedOutput = kernel.newDerivatives();
        var freshOutput = kernel.newDerivatives();
        int compared = 0;
        List<State> states = new ArrayList<>(pilotStates());
        states.addAll(pilotStates());
        for (State state : states) {
            for (PengRobinsonKernel.Root root : PengRobinsonKernel.Root.values()) {
                try {
                    kernel.evaluateDerivatives(state.t, state.p, state.x, root, reused, reusedOutput);
                } catch (RuntimeException unavailable) { continue; }
                kernel.evaluateDerivatives(state.t, state.p, state.x, root, kernel.newWorkspace(), freshOutput);
                compared += same(freshOutput.evaluation().logFugacityCoefficientsView(),
                        reusedOutput.evaluation().logFugacityCoefficientsView(), "ln phi");
                compared += same(freshOutput.evaluation().daMixDt(), reusedOutput.evaluation().daMixDt(), "da/dT");
                compared += same(freshOutput.dLogPhiDtView(), reusedOutput.dLogPhiDtView(), "d ln phi/dT");
                compared += same(freshOutput.dResidualEnthalpyDt(), reusedOutput.dResidualEnthalpyDt(), "dh^R/dT");
                for (int i = 0; i < 4; i++) compared += same(freshOutput.dLogPhiDnRowView(i), reusedOutput.dLogPhiDnRowView(i), "dn");
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
    }

    @Test void refusesMismatchedModelsAndUncachesNonFinitePairs() {
        double[] tc = PairInteractionsTest.TC, pc = PairInteractionsTest.PC, omega = PairInteractionsTest.OMEGA;
        assertThrows(IllegalArgumentException.class, () -> new PengRobinsonKernel(tc, pc, omega, PairInteractions.builder(3).build(),
                1, 2000, 1, 1e8));
        // An exponent B/A - 1 of 1e10 overflows (298.15/T)^n below 298.15 K: k_ij is infinite there and refused.
        var model = PairInteractionsTest.groupContribution(PairInteractions.builder(4), 0, 1, new int[] {1, 0}, new int[] {0, 1},
                new double[][] {{0, 1e-10}, {1e-10, 0}}, new double[][] {{0, 1}, {1, 0}}).build();
        var kernel = new PengRobinsonKernel(tc, pc, omega, model, 1, 2000, 1, 1e8);
        var workspace = kernel.newWorkspace();
        assertThrows(IllegalArgumentException.class, () -> kernel.prepareTemperature(200.0, workspace));
        kernel.prepareTemperature(400.0, workspace);
        assertTrue(Double.isFinite(PairInteractionsTest.kernelValues(kernel, model, workspace, 400.0, 0)[0]));
        assertThrows(IllegalArgumentException.class, () -> kernel.prepareTemperature(200.0, workspace),
                "a refused temperature must not be cached");
    }

    // ---------------------------------------------------------------------------------------------------------------

    record State(double t, double p, double[] x, double[] fractions) {
        State(double t, double p, double[] raw) {
            this(t, p, normalized(raw), normalized(raw));
        }
        private static double[] normalized(double[] raw) {
            double total = 0.0;
            for (double value : raw) total += value;
            double[] fractions = new double[raw.length];
            for (int i = 0; i < raw.length; i++) fractions[i] = raw[i] / total;
            return fractions;
        }
        @Override public String toString() { return "T=" + t + " P=" + p; }
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

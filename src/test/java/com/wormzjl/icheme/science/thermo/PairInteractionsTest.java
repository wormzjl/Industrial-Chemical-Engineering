package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The E-PPR78 group-contribution {@code k_ij(T)} of {@link PairInteractions}, read through the kernel that evaluates it
 * ({@link PengRobinsonKernel} on a pair model), against three independent references: the textbook
 * formula evaluated directly here (to 1e-12), central differences of its own values (first and second derivative, 1e-6
 * of scale), and the table of appendix A of {@code P3_PILOT_ENGINE_PLAN.md} (four decimals).
 *
 * <p>The pilot species are single-group molecules (Jaubert et al. 2022 decomposition: N2, CH4, C2H6, CO2 are each one
 * group), with the six group pairs of Table S4 of the supporting information of Jaubert et al. 2022 (A and B in MPa; the
 * values of the bundled record {@code eppr78_2022}, identical to the Clapeyron.jl transcription used before P3 WP2b) and
 * the PR78 constants of the bundled records {@code fluid_nitrogen}, {@code tjl20_methane}, {@code tjl19_ethane} and the
 * P2 record {@code pilot_carbon_dioxide}.</p>
 */
class PairInteractionsTest {
    static final String[] NAMES = {"Nitrogen", "Methane", "Ethane", "CarbonDioxide"};
    static final double[] TC = {126.192, 190.564, 305.32, 304.1282};
    static final double[] PC = {3395800.0, 4599200.0, 4872000.0, 7377300.0};
    static final double[] OMEGA = {0.0372, 0.01142, 0.099, 0.22394};
    static final int N2 = 0, CH4 = 1, C2H6 = 2, CO2 = 3;
    /**
     * Table S4 (Jaubert et al. 2022 supporting information) cells of the six pilot group pairs, MPa; unchanged from the
     * Clapeyron.jl {@code EPPR78_unlike.csv} (commit 0778184) rows this test first used.
     */
    static final double[][] PAIRS_A_B = {
            {CH4, C2H6, 9.951, 13.73},
            {CH4, CO2, 136.6, 214.8},
            {CH4, N2, 30.88, 37.06},
            {C2H6, CO2, 136.2, 235.7},
            {C2H6, N2, 61.59, 84.92},
            {CO2, N2, 113.9, 212.4}};
    static final double[] APPENDIX_TEMPERATURES = {100, 150, 200, 250, 300, 400, 600, 900};
    /** Appendix A of {@code P3_PILOT_ENGINE_PLAN.md}, rows in {@link #PAIRS_A_B} order. */
    static final double[][] APPENDIX_A = {
            {0.0072, 0.0063, 0.0059, 0.0057, 0.0057, 0.0060, 0.0075, 0.0121},
            {0.1213, 0.1074, 0.1040, 0.1057, 0.1104, 0.1259, 0.1747, 0.3010},
            {0.0337, 0.0315, 0.0295, 0.0276, 0.0256, 0.0205, 0.0030, -0.0729},
            {0.1734, 0.1453, 0.1331, 0.1279, 0.1267, 0.1308, 0.1543, 0.2184},
            {0.0610, 0.0492, 0.0405, 0.0332, 0.0262, 0.0122, -0.0244, -0.1472},
            {0.0955, 0.0371, 0.0050, -0.0158, -0.0306, -0.0505, -0.0712, -0.0675}};

    /** The pilot model: one group per species, the group list in basis order. */
    static PairInteractions pilotModel() {
        double[][] a = new double[4][4];
        double[][] b = new double[4][4];
        for (double[] row : PAIRS_A_B) {
            int k = (int) row[0];
            int l = (int) row[1];
            a[k][l] = a[l][k] = row[2];
            b[k][l] = b[l][k] = row[3];
        }
        var builder = PairInteractions.builder(4);
        for (int i = 0; i < 4; i++) {
            for (int j = i + 1; j < 4; j++) groupContribution(builder, i, j, single(i), single(j), a, b);
        }
        return builder.build();
    }

    /**
     * The E-PPR78 rule for the pair {@code (i, j)} from two group decompositions over one symmetric, zero-diagonal group
     * table ({@code A_kl}, {@code B_kl} in MPa): the double sum of the rule folded to one term per group pair
     * {@code k < l} of weight {@code -(alpha_ik - alpha_jk)(alpha_il - alpha_jl)}, which is what the catalog's
     * {@code GroupContributionInteractions} resolves and production passes to {@link PairInteractions.Builder#groupTerms}.
     */
    static PairInteractions.Builder groupContribution(PairInteractions.Builder builder, int i, int j, int[] countsI,
                                                      int[] countsJ, double[][] a, double[][] b) {
        int groups = a.length;
        double[] alphaI = fractions(countsI);
        double[] alphaJ = fractions(countsJ);
        int terms = 0;
        double[] weights = new double[groups * groups];
        double[] aPascal = new double[groups * groups];
        double[] exponents = new double[groups * groups];
        for (int k = 0; k < groups; k++) {
            double dk = alphaI[k] - alphaJ[k];
            if (dk == 0.0) continue;
            for (int l = k + 1; l < groups; l++) {
                double dl = alphaI[l] - alphaJ[l];
                if (dl == 0.0 || a[k][l] == 0.0) continue;
                weights[terms] = -(dk * dl);
                aPascal[terms] = a[k][l] * 1.0e6;
                exponents[terms] = b[k][l] / a[k][l] - 1.0;
                terms++;
            }
        }
        return builder.groupTerms(i, j, java.util.Arrays.copyOf(weights, terms), java.util.Arrays.copyOf(aPascal, terms),
                java.util.Arrays.copyOf(exponents, terms));
    }

    private static double[] fractions(int[] counts) {
        double total = 0.0;
        for (int count : counts) total += count;
        double[] alpha = new double[counts.length];
        for (int k = 0; k < counts.length; k++) alpha[k] = counts[k] / total;
        return alpha;
    }

    /** The constant model of a symmetric, zero-diagonal matrix: one pair per nonzero {@code i < j} entry. */
    static PairInteractions constant(double[][] matrix) {
        var builder = PairInteractions.builder(matrix.length);
        for (int i = 0; i < matrix.length; i++) {
            for (int j = i + 1; j < matrix.length; j++) if (matrix[i][j] != 0.0) builder.constant(i, j, matrix[i][j]);
        }
        return builder.build();
    }

    /** The index of the pair {@code (i, j)} in the model's pair order, or -1 when it is not declared. */
    static int pairIndex(PairInteractions model, int i, int j) {
        for (int pair = 0; pair < model.pairCount(); pair++) {
            if (model.first(pair) == Math.min(i, j) && model.second(pair) == Math.max(i, j)) return pair;
        }
        return -1;
    }

    /**
     * {@code k_ij(T)} (order 0), {@code dk_ij/dT} (1) or {@code d^2k_ij/dT^2} (2) per pair, as the kernel's
     * {@code prepareTemperature} fills them: the model evaluated on the kernel's own {@code sqrt(a_i)}, root
     * derivatives and co-volumes.
     */
    static double[] kernelValues(PengRobinsonKernel kernel, PairInteractions model, PengRobinsonKernel.Workspace workspace,
                                 double t, int order) {
        kernel.prepareTemperature(t, workspace);
        int n = kernel.componentCount();
        double[] rootDt = new double[n];
        double[] rootDt2 = new double[n];
        double[] coVolumes = new double[n];
        if (model.temperatureDependent()) kernel.prepareRootDerivatives(t, workspace, rootDt, rootDt2);
        for (int i = 0; i < n; i++) coVolumes[i] = kernel.coVolume(i);
        double[][] values = new double[3][model.pairCount()];
        model.evaluate(t, workspace.attractionRootsView(), rootDt, rootDt2, coVolumes, values[0], values[1], values[2]);
        return values[order];
    }

    static PengRobinsonKernel pilotKernel(double[] criticalPressures) {
        return new PengRobinsonKernel(TC, criticalPressures, OMEGA, pilotModel(),
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
    }

    private static int[] single(int group) {
        int[] counts = new int[4];
        counts[group] = 1;
        return counts;
    }

    @Test void pilotConstantsAreTheBundledRecords() {
        var network = MaterialCatalog.bundled().requirePackage("icheme:tjl20_methane_nitrogen");
        for (int s = 0; s < 3; s++) {
            var pr = network.properties().get(network.components().indexOf(NAMES[s])).pr();
            assertEquals(TC[s], pr.criticalTemperature(), 0.0, NAMES[s]);
            assertEquals(PC[s], pr.criticalPressure(), 0.0, NAMES[s]);
            assertEquals(OMEGA[s], pr.acentricFactor(), 0.0, NAMES[s]);
        }
    }

    /**
     * Appendix A to four decimals. With the bundled ethane record ({@code Pc = 4.872 MPa}) 46 of the 48 cells round to
     * the table; C2H6/N2 at 200 K and 300 K are 0.040560 and 0.026254, one unit above the table's last digit. With
     * CoolProp's ethane {@code Pc = 4.8722 MPa} every cell rounds to the table, which identifies the appendix's input.
     */
    @Test void reproducesAppendixA() {
        var bundled = table(pilotKernel(PC));
        double[] coolPropEthane = PC.clone();
        coolPropEthane[C2H6] = 4872200.0;
        var appendixInput = table(pilotKernel(coolPropEthane));
        int bundledMatches = 0;
        StringBuilder printed = new StringBuilder("E-PPR78 k_ij(T), kernel constants (bundled records):\n");
        for (int p = 0; p < PAIRS_A_B.length; p++) {
            printed.append(String.format(Locale.ROOT, "  %-9s", NAMES[(int) PAIRS_A_B[p][0]].substring(0, 3) + "/"
                    + NAMES[(int) PAIRS_A_B[p][1]].substring(0, 3)));
            for (int t = 0; t < APPENDIX_TEMPERATURES.length; t++) {
                double expected = APPENDIX_A[p][t];
                printed.append(String.format(Locale.ROOT, " %9.6f", bundled[p][t]));
                assertEquals(expected, appendixInput[p][t], 0.5e-4, "appendix input, pair " + p + " at " + APPENDIX_TEMPERATURES[t]);
                boolean named = p == 4 && (APPENDIX_TEMPERATURES[t] == 200 || APPENDIX_TEMPERATURES[t] == 300);
                if (named) {
                    assertEquals(expected + 1.0e-4, bundled[p][t], 0.5e-4, "named rounding cell");
                } else {
                    assertEquals(expected, bundled[p][t], 0.5e-4, "bundled, pair " + p + " at " + APPENDIX_TEMPERATURES[t]);
                    bundledMatches++;
                }
            }
            printed.append('\n');
        }
        System.out.print(printed);
        assertEquals(46, bundledMatches);
    }

    private static double[][] table(PengRobinsonKernel kernel) {
        var model = pilotModel();
        var workspace = kernel.newWorkspace();
        double[][] values = new double[PAIRS_A_B.length][APPENDIX_TEMPERATURES.length];
        for (int t = 0; t < APPENDIX_TEMPERATURES.length; t++) {
            double[] k = kernelValues(kernel, model, workspace, APPENDIX_TEMPERATURES[t], 0);
            for (int p = 0; p < PAIRS_A_B.length; p++) {
                values[p][t] = k[pairIndex(model, (int) PAIRS_A_B[p][0], (int) PAIRS_A_B[p][1])];
            }
        }
        return values;
    }

    /** The textbook formula, evaluated here from the critical constants alone, on single-group and multi-group pairs. */
    @Test void matchesTheClosedFormToOneInATrillion() {
        var kernel = pilotKernel(PC);
        var model = pilotModel();
        var workspace = kernel.newWorkspace();
        double worst = 0.0;
        int compared = 0;
        for (double t = 90.0; t <= 1200.0; t += 5.0) {
            double[] k = kernelValues(kernel, model, workspace, t, 0);
            for (double[] row : PAIRS_A_B) {
                int i = (int) row[0];
                int j = (int) row[1];
                double energy = row[2] * 1.0e6 * Math.pow(298.15 / t, row[3] / row[2] - 1.0);
                double expected = closedForm(energy, i, j, t, TC, PC, OMEGA);
                double actual = k[pairIndex(model, i, j)];
                worst = Math.max(worst, Math.abs(expected - actual));
                compared++;
            }
        }

        // A multi-group pair exercises the double sum: synthetic group table, molecules 2 G0 + 1 G1 and 1 G0 + 2 G1 + 1 G2.
        double[][] a = {{0, 65.54, 214.9}, {65.54, 0, 39.05}, {214.9, 39.05, 0}};
        double[][] b = {{0, 105.7, 294.9}, {105.7, 0, 40.59}, {294.9, 40.59, 0}};
        int[] countsI = {2, 1, 0};
        int[] countsJ = {1, 2, 1};
        var groups = groupContribution(PairInteractions.builder(2), 0, 1, countsI, countsJ, a, b).build();
        double[] tc = {TC[C2H6], TC[CO2]};
        double[] pc = {PC[C2H6], PC[CO2]};
        double[] omega = {OMEGA[C2H6], OMEGA[CO2]};
        var multi = new PengRobinsonKernel(tc, pc, omega, groups, Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
        var multiWorkspace = multi.newWorkspace();
        double[] alphaI = {2.0 / 3.0, 1.0 / 3.0, 0.0};
        double[] alphaJ = {0.25, 0.5, 0.25};
        for (double t = 90.0; t <= 1200.0; t += 5.0) {
            double energy = 0.0;
            for (int k = 0; k < 3; k++) {
                for (int l = 0; l < 3; l++) {
                    if (a[k][l] == 0.0) continue;
                    energy += -0.5 * (alphaI[k] - alphaJ[k]) * (alphaI[l] - alphaJ[l])
                            * a[k][l] * 1.0e6 * Math.pow(298.15 / t, b[k][l] / a[k][l] - 1.0);
                }
            }
            double expected = closedForm(energy, 0, 1, t, tc, pc, omega);
            worst = Math.max(worst, Math.abs(expected - kernelValues(multi, groups, multiWorkspace, t, 0)[0]));
            compared++;
        }
        System.out.printf(Locale.ROOT, "E-PPR78 closed form: %d comparisons, max |dk| = %.3e%n", compared, worst);
        assertTrue(worst <= 1.0e-12, "closed form deviation " + worst);
    }

    /** {@code k = [E - (sqrt(a_i)/b_i - sqrt(a_j)/b_j)^2] / [2 sqrt(a_i a_j)/(b_i b_j)]} on Soave PR78 constants. */
    private static double closedForm(double energy, int i, int j, double t, double[] tc, double[] pc, double[] omega) {
        double r = PengRobinsonKernel.GAS_CONSTANT;
        double[] a = new double[2];
        double[] b = new double[2];
        int[] index = {i, j};
        for (int s = 0; s < 2; s++) {
            int c = index[s];
            double w = omega[c];
            double kappa = w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
                    : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
            double alpha = 1.0 + kappa * (1.0 - Math.sqrt(t / tc[c]));
            a[s] = 0.45724 * r * r * tc[c] * tc[c] / pc[c] * alpha * alpha;
            b[s] = 0.07780 * r * tc[c] / pc[c];
        }
        double difference = Math.sqrt(a[0]) / b[0] - Math.sqrt(a[1]) / b[1];
        return (energy - difference * difference) / (2.0 * Math.sqrt(a[0] * a[1]) / (b[0] * b[1]));
    }

    /**
     * {@code dk/dT} against a central difference of {@code k} and {@code d^2k/dT^2} against central differences of both
     * {@code k} and the analytic {@code dk/dT}; 1e-6 of the scale {@code max(|derivative|, |k|/T^n)}. Each difference is
     * Richardson-extrapolated from steps {@code h} and {@code h/2} ({@code (4 D(h/2) - D(h))/3}, truncation of order
     * {@code h^4}): towards 1200 K the nitrogen pairs' {@code k_ij} varies fast because nitrogen's Soave
     * {@code sqrt(a)} approaches its zero at about 1390 K, and a plain second difference's truncation would dominate.
     */
    @Test void derivativesMatchCentralDifferences() {
        var kernel = pilotKernel(PC);
        var model = pilotModel();
        var workspace = kernel.newWorkspace();
        int pairs = model.pairCount();
        double worstFirst = 0.0;
        double worstSecondFromValues = 0.0;
        double worstSecondFromFirst = 0.0;
        int compared = 0;
        for (double t : new double[] {92, 100, 120, 150, 175, 200, 225, 250, 275, 300, 350, 400, 500, 600, 750, 900, 1050, 1200}) {
            double h = 1.0e-3 * t;
            double[] k = kernelValues(kernel, model, workspace, t, 0);
            double[] kDt = kernelValues(kernel, model, workspace, t, 1);
            double[] kDt2 = kernelValues(kernel, model, workspace, t, 2);
            double[] kPlus = kernelValues(kernel, model, workspace, t + h, 0);
            double[] kMinus = kernelValues(kernel, model, workspace, t - h, 0);
            double[] kHalfPlus = kernelValues(kernel, model, workspace, t + h / 2, 0);
            double[] kHalfMinus = kernelValues(kernel, model, workspace, t - h / 2, 0);
            double[] kDtPlus = kernelValues(kernel, model, workspace, t + h, 1);
            double[] kDtMinus = kernelValues(kernel, model, workspace, t - h, 1);
            double[] kDtHalfPlus = kernelValues(kernel, model, workspace, t + h / 2, 1);
            double[] kDtHalfMinus = kernelValues(kernel, model, workspace, t - h / 2, 1);
            for (int p = 0; p < pairs; p++) {
                double first = richardson((kPlus[p] - kMinus[p]) / (2.0 * h), (kHalfPlus[p] - kHalfMinus[p]) / h);
                double secondFromFirst = richardson((kDtPlus[p] - kDtMinus[p]) / (2.0 * h), (kDtHalfPlus[p] - kDtHalfMinus[p]) / h);
                double secondFromValues = richardson((kPlus[p] - 2.0 * k[p] + kMinus[p]) / (h * h),
                        (kHalfPlus[p] - 2.0 * k[p] + kHalfMinus[p]) / (h * h / 4.0));
                double firstScale = Math.max(Math.abs(kDt[p]), Math.abs(k[p]) / t);
                double secondScale = Math.max(Math.abs(kDt2[p]), Math.abs(k[p]) / (t * t));
                worstFirst = Math.max(worstFirst, Math.abs(first - kDt[p]) / firstScale);
                worstSecondFromFirst = Math.max(worstSecondFromFirst, Math.abs(secondFromFirst - kDt2[p]) / secondScale);
                worstSecondFromValues = Math.max(worstSecondFromValues, Math.abs(secondFromValues - kDt2[p]) / secondScale);
                compared++;
            }
        }
        System.out.printf(Locale.ROOT, "E-PPR78 derivatives vs central differences (%d pair-temperatures): dk/dT %.2e,"
                + " d2k/dT2 from dk/dT %.2e, from k %.2e%n", compared, worstFirst, worstSecondFromFirst, worstSecondFromValues);
        assertTrue(worstFirst <= 1.0e-6, "dk/dT " + worstFirst);
        assertTrue(worstSecondFromFirst <= 1.0e-6, "d2k/dT2 from dk/dT " + worstSecondFromFirst);
        assertTrue(worstSecondFromValues <= 1.0e-6, "d2k/dT2 from k " + worstSecondFromValues);
    }

    /** {@code (4 D(h/2) - D(h)) / 3} for a central difference {@code D} whose error is even in {@code h}. */
    static double richardson(double full, double half) {
        return (4.0 * half - full) / 3.0;
    }

    /** Pre-resolved terms (weight, A in Pa, B/A - 1), the form the catalog's group rule resolves, give the same model. */
    @Test void resolvedTermsAreTheGroupRule() {
        var builder = PairInteractions.builder(4);
        for (double[] row : PAIRS_A_B) {
            builder.groupTerms((int) row[0], (int) row[1], new double[] {1.0}, new double[] {row[2] * 1.0e6},
                    new double[] {row[3] / row[2] - 1.0});
        }
        var terms = builder.build();
        var fromTerms = new PengRobinsonKernel(TC, PC, OMEGA, terms, Double.MIN_VALUE, Double.MAX_VALUE,
                Double.MIN_VALUE, Double.MAX_VALUE);
        var fromGroups = pilotKernel(PC);
        var groups = pilotModel();
        var termsWorkspace = fromTerms.newWorkspace();
        var groupsWorkspace = fromGroups.newWorkspace();
        for (double t = 90.0; t <= 1200.0; t += 55.0) {
            for (int order = 0; order < 3; order++) {
                assertArrayEquals(kernelValues(fromGroups, groups, groupsWorkspace, t, order),
                        kernelValues(fromTerms, terms, termsWorkspace, t, order), 0.0);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(2)
                .groupTerms(0, 1, new double[] {1.0}, new double[] {1.0e7}, new double[0]));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(2)
                .groupTerms(0, 1, new double[] {1.0}, new double[] {Double.POSITIVE_INFINITY}, new double[] {0.5}));
    }

    @Test void constantPairsAreConstants() {
        double[][] matrix = {{0, 0.03, 0}, {0.03, 0, -0.01}, {0, -0.01, 0}};
        var model = constant(matrix);
        assertEquals(2, model.pairCount());
        assertFalse(model.temperatureDependent());
        assertEquals(0, model.first(0));
        assertEquals(1, model.second(0));
        assertEquals(1, model.first(1));
        assertEquals(2, model.second(1));
        double[] value = new double[2];
        double[] first = {Double.NaN, Double.NaN};
        double[] second = {Double.NaN, Double.NaN};
        model.evaluate(150.0, new double[3], new double[0], new double[0], new double[3], value, first, second);
        assertArrayEquals(new double[] {0.03, -0.01}, value, 0.0);
        assertArrayEquals(new double[2], first, 0.0);
        assertArrayEquals(new double[2], second, 0.0);
        assertEquals(0, PairInteractions.builder(3).build().pairCount());
    }

    @Test void refusesMalformedModels() {
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(0));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(3).constant(1, 1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(3).constant(0, 3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(3).constant(0, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(3).constant(0, 1, 0.1).constant(1, 0, 0.2));
        assertThrows(IllegalArgumentException.class, () -> PairInteractions.builder(2).constant(0, 1, 0.1)
                .groupTerms(1, 0, new double[] {1.0}, new double[] {1.0e7}, new double[] {0.5}));
        // Identical group make-up: no term survives, so E = 0 and k_ij is the structural term alone (a declared pair).
        var identical = PairInteractions.builder(2).groupTerms(0, 1, new double[0], new double[0], new double[0]).build();
        assertEquals(1, identical.pairCount());
        assertTrue(identical.temperatureDependent());
    }
}

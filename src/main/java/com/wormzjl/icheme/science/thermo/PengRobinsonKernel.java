package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Arrays;
import java.util.Objects;

/**
 * Allocation-free PR78 kernel with one mixing rule (W3b step 1b, design N8). One {@link Workspace} belongs to one solve;
 * liquid and vapor evaluations at a common temperature share its temperature-dependent pure-component arithmetic.
 * Promoted from the V3 column's package, the critical constants read into arrays by the constructor, so the column, the
 * fluid network and the equilibrium engine evaluate their phases on the same arithmetic.
 *
 * <p>The rule is Huron-Vidal's at infinite pressure for PR ({@link HuronVidalExcess}): a/b = sum_i x_i a_i/b_i - gE/Lambda,
 * the co-volume-weighted NRTL gE. A classical pair (alpha = 0, energies from its {@code k_ij(T)}) is the quadratic rule
 * {@code a_ij = sqrt(a_i a_j)(1 - k_ij)}; where no NRTL pair is active the whole rule is the quadratic one. So the
 * kernel forms the quadratic part on its pair list, {@code O(n + pairs)} (the rank-one sum less the pairs whose
 * {@code k_ij} is not zero), and the exponential sums only where the kernel has NRTL pairs. Every constructor reaches the
 * same rule: a constant matrix, a {@link PairInteractions} model, or a {@link HuronVidalExcess} with a Mathias-Copeman
 * alpha per compound.</p>
 *
 * <p>An evaluation costs what the caller's mixture carries: the caller names its support on the workspace
 * ({@link Workspace#carry}, its feed's nonzero amounts), and the kernel prepares, sums and differentiates over the
 * support only, in ascending order. The terms it leaves out are exact zeros, so every output on the support keeps its
 * bits against the whole list; outside it the outputs are zero. A new workspace carries the whole list.</p>
 */
public final class PengRobinsonKernel {
    public static final double GAS_CONSTANT = 8.31446261815324;
    private static final double SQRT_TWO = Math.sqrt(2.0);
    private static final double ROOT_EPSILON = 1.0e-12;
    private static final double COALESCENCE_DISCRIMINANT_TOLERANCE = 1.0e-16;
    private static final int MAXIMUM_ROOT_REFINEMENTS = 4;
    /**
     * How flat the cubic may be at the selected root before its derivative is refused. Every state derivative divides by
     * this slope, so its smallness makes the whole bundle meaningless; the bound is relative to the size of the terms
     * the slope is assembled from, so it measures cancellation, and lies far below any separation a column state
     * reaches (refusing costs the caller a fallback to differencing).
     */
    private static final double COALESCENCE_SLOPE_TOLERANCE = 1.0e-10;

    private final int count;
    private final double[] criticalTemperatures;
    private final double[] criticalPressures;
    private final double[] acentricFactors;
    private final double[] criticalA;
    private final double[] coVolumes;
    private final double[] kappas;
    /** True when the kernel has no pair at all: the quadratic part is then the rank-one product. */
    private final boolean rankOneMixing;
    /**
     * True when every listed pair is a constant (no line, no model pair): each term a pair's {@code dk/dT} or
     * {@code d^2k/dT^2} adds is then an exact zero, and the passes that form only such terms are skipped.
     */
    private final boolean constantPairs;
    /**
     * The classical pairs whose {@code k_ij(T)} may be nonzero, {@code i < j}: a constant or a line {@code k0 + k1 T}
     * ({@link #pairModel} -1), or the pair {@link #pairModel} of {@link #interactions}. Their values per temperature live
     * in the workspace.
     */
    private final int[] pairFirst;
    private final int[] pairSecond;
    private final double[] pairConstant;
    private final double[] pairSlope;
    /** The fitted ends of each listed pair's line ({@link HuronVidalExcess#held}); infinite for a constant. */
    private final double[] pairLow, pairHigh;
    private final int[] pairModel;
    /** {@code pairIndex[i][j]}: the pair of {@code (i, j)} in the list above, or -1. */
    private final int[][] pairIndex;
    /** The interaction model whose pairs are on the list; null when none is. */
    private final PairInteractions interactions;
    /**
     * Per compound, true when its alpha is Mathias-Copeman with the coefficients below; null when every compound uses
     * the Soave form with PR78's kappa.
     */
    private final boolean[] mathiasCopeman;
    private final double[] alphaFirst;
    private final double[] alphaSecond;
    private final double[] alphaThird;
    /**
     * Whether any pair is NRTL. Then the NRTL cells are listed per centre: cells {@code cellStart[i]} to
     * {@code cellStart[i + 1] - 1} are the partners {@code j = cellPartner[c]} whose energy {@code dg_ji} of {@code j}
     * around {@code i} is the NRTL line {@code dg_ji = intercept + slope T} with {@code alpha_ij}; {@code cellIndex[i][j]}
     * is the cell of {@code (i, j)}, or -1 for a classical pair. Empty arrays without NRTL pairs.
     */
    private final boolean excess;
    private final int[] cellStart;
    private final int[] cellPartner;
    private final int[][] cellIndex;
    private final double[] cellAlpha;
    private final double[] cellIntercept;
    private final double[] cellSlope;
    /** The fitted ends of each cell's energy line ({@link HuronVidalExcess#held}) and its fade temperature (infinite: none). */
    private final double[] cellLow, cellHigh, cellFade;
    private final double minimumTemperatureKelvin;
    private final double maximumTemperatureKelvin;
    private final double minimumPressurePascal;
    private final double maximumPressurePascal;

    /**
     * The kernel on a constant interaction matrix: each nonzero {@code k_ij} a classical pair.
     *
     * @param criticalTemperaturesKelvin per component, in the caller's basis order
     * @param binaryInteractions square, symmetric, zero diagonal; copied
     */
    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors, matrixPairs(criticalTemperaturesKelvin, binaryInteractions),
                null, minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal);
    }

    /**
     * The kernel on a pair-interaction model: each of its pairs a classical pair whose {@code k_ij(T)} the model gives at
     * each temperature, with its two temperature derivatives.
     *
     * @param interactions over the same basis, in the caller's order
     */
    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            PairInteractions interactions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors,
                modelPairs(criticalTemperaturesKelvin, Objects.requireNonNull(interactions, "interactions")), null,
                minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal);
    }

    /**
     * The kernel on a Huron-Vidal excess model, and an optional Mathias-Copeman alpha per compound:
     * {@code alpha = (1 + c1 u + c2 u^2 + c3 u^3)^2}, {@code u = 1 - sqrt(T/Tc)}, and above {@code Tc} only {@code c1}
     * ({@code alpha = (1 + c1 u)^2}, so value and slope are continuous at {@code Tc}). A compound without coefficients
     * keeps the Soave form with PR78's kappa of its acentric factor.
     *
     * @param excess over the same basis, in the caller's order
     * @param mathiasCopeman null, or one entry per compound: null for the Soave form, or {@code {c1, c2, c3}}; copied
     */
    public static PengRobinsonKernel huronVidal(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            HuronVidalExcess excess, double[][] mathiasCopeman,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        return new PengRobinsonKernel(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors,
                excessPairs(criticalTemperaturesKelvin, Objects.requireNonNull(excess, "excess")), mathiasCopeman,
                minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal);
    }

    /** The pairs of one kernel before the constructor copies them. */
    private record Pairs(int count, int[] first, int[] second, double[] constant, double[] slope, int[] model,
                         PairInteractions interactions, boolean[] nrtl, double[] alpha, double[] intercept, double[] energySlope,
                         double[] low, double[] high, double[] cellLow, double[] cellHigh, double[] cellFade) {
        /** Pairs without lines: constants and model pairs (infinite ends). */
        Pairs(int count, int[] first, int[] second, double[] constant, double[] slope, int[] model,
              PairInteractions interactions, boolean[] nrtl, double[] alpha, double[] intercept, double[] energySlope) {
            this(count, first, second, constant, slope, model, interactions, nrtl, alpha, intercept, energySlope,
                    filled(first.length, Double.NEGATIVE_INFINITY), filled(first.length, Double.POSITIVE_INFINITY), null, null, null);
        }

        private static double[] filled(int length, double value) {
            double[] values = new double[length];
            Arrays.fill(values, value);
            return values;
        }
    }

    private static Pairs matrixPairs(double[] criticalTemperaturesKelvin, double[][] binaryInteractions) {
        int n = Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin").length;
        Objects.requireNonNull(binaryInteractions, "binaryInteractions");
        if (binaryInteractions.length != n) throw new IllegalArgumentException("Invalid BIP matrix dimension");
        int pairs = 0;
        for (int i = 0; i < n; i++) {
            if (binaryInteractions[i] == null || binaryInteractions[i].length != n) throw new IllegalArgumentException("Invalid BIP matrix row");
            for (int j = 0; j < n; j++) {
                double value = binaryInteractions[i][j];
                if (!Double.isFinite(value) || Math.abs(value - binaryInteractions[j][i]) > 1.0e-14
                        || (i == j && Math.abs(value) > 1.0e-14)) {
                    throw new IllegalArgumentException("BIP matrix must be finite, symmetric, and zero diagonal");
                }
                if (j > i && value != 0.0) pairs++;
            }
        }
        int[] first = new int[pairs], second = new int[pairs], model = new int[pairs];
        double[] constant = new double[pairs];
        Arrays.fill(model, -1);
        int at = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (binaryInteractions[i][j] == 0.0) continue;
            first[at] = i;
            second[at] = j;
            constant[at++] = binaryInteractions[i][j];
        }
        return new Pairs(n, first, second, constant, new double[pairs], model, null, null, null, null, null);
    }

    private static Pairs modelPairs(double[] criticalTemperaturesKelvin, PairInteractions interactions) {
        int n = Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin").length;
        if (interactions.componentCount() != n) throw new IllegalArgumentException("Interaction model basis does not match the components");
        int pairs = interactions.pairCount();
        int[] first = new int[pairs], second = new int[pairs], model = new int[pairs];
        for (int pair = 0; pair < pairs; pair++) {
            first[pair] = interactions.first(pair);
            second[pair] = interactions.second(pair);
            model[pair] = pair;
        }
        return new Pairs(n, first, second, new double[pairs], new double[pairs], model, interactions, null, null, null, null);
    }

    private static Pairs excessPairs(double[] criticalTemperaturesKelvin, HuronVidalExcess excess) {
        int n = Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin").length;
        if (excess.componentCount() != n) throw new IllegalArgumentException("Excess model basis does not match the components");
        int pairs = 0;
        boolean anyNrtl = false;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (!excess.classical(i, j)) {
                anyNrtl = true;
            } else if (excess.interactionPair(i, j) >= 0 || excess.interaction(i, j) != 0.0 || excess.interactionSlope(i, j) != 0.0) {
                pairs++;
            }
        }
        int[] first = new int[pairs], second = new int[pairs], model = new int[pairs];
        double[] constant = new double[pairs], slope = new double[pairs], low = new double[pairs], high = new double[pairs];
        int at = 0;
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) {
            if (!excess.classical(i, j)) continue;
            int m = excess.interactionPair(i, j);
            if (m < 0 && excess.interaction(i, j) == 0.0 && excess.interactionSlope(i, j) == 0.0) continue;
            low[at] = excess.lineLow(i, j);
            high[at] = excess.lineHigh(i, j);
            first[at] = i;
            second[at] = j;
            model[at] = m;
            constant[at] = excess.interaction(i, j);
            slope[at++] = excess.interactionSlope(i, j);
        }
        boolean[] nrtl = null;
        double[] alpha = null, intercept = null, energySlope = null, cellLow = null, cellHigh = null, cellFade = null;
        if (anyNrtl) {
            int cells = n * n;
            nrtl = new boolean[cells];
            alpha = new double[cells];
            intercept = new double[cells];
            energySlope = new double[cells];
            cellLow = new double[cells];
            cellHigh = new double[cells];
            cellFade = new double[cells];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j || excess.classical(i, j)) continue;
                    int cell = i * n + j;
                    nrtl[cell] = true;
                    alpha[cell] = excess.alpha(i, j);
                    intercept[cell] = excess.energyIntercept(j, i);
                    energySlope[cell] = excess.energySlope(j, i);
                    cellLow[cell] = excess.lineLow(i, j);
                    cellHigh[cell] = excess.lineHigh(i, j);
                    cellFade[cell] = excess.lineFade(i, j);
                }
            }
        }
        return new Pairs(n, first, second, constant, slope, model, excess.interactions(), nrtl, alpha, intercept, energySlope,
                low, high, cellLow, cellHigh, cellFade);
    }

    private PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            Pairs pairs, double[][] mathiasCopemanCoefficients,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin");
        Objects.requireNonNull(criticalPressuresPascal, "criticalPressuresPascal");
        Objects.requireNonNull(acentricFactors, "acentricFactors");
        this.minimumTemperatureKelvin = minimumTemperatureKelvin;
        this.maximumTemperatureKelvin = maximumTemperatureKelvin;
        this.minimumPressurePascal = minimumPressurePascal;
        this.maximumPressurePascal = maximumPressurePascal;
        this.count = criticalTemperaturesKelvin.length;
        if (criticalPressuresPascal.length != count || acentricFactors.length != count) {
            throw new IllegalArgumentException("Invalid critical-property dimension");
        }
        this.criticalTemperatures = criticalTemperaturesKelvin.clone();
        this.criticalPressures = criticalPressuresPascal.clone();
        this.acentricFactors = acentricFactors.clone();
        this.criticalA = new double[count];
        this.coVolumes = new double[count];
        this.kappas = new double[count];
        for (int i = 0; i < count; i++) {
            criticalA[i] = 0.45724 * GAS_CONSTANT * GAS_CONSTANT
                    * criticalTemperatures[i] * criticalTemperatures[i]
                    / criticalPressures[i];
            coVolumes[i] = 0.07780 * GAS_CONSTANT * criticalTemperatures[i]
                    / criticalPressures[i];
            kappas[i] = kappa(this.acentricFactors[i]);
        }
        if (mathiasCopemanCoefficients == null) {
            this.mathiasCopeman = null;
            this.alphaFirst = null;
            this.alphaSecond = null;
            this.alphaThird = null;
        } else {
            if (mathiasCopemanCoefficients.length != count) throw new IllegalArgumentException("Invalid alpha coefficient dimension");
            this.mathiasCopeman = new boolean[count];
            this.alphaFirst = new double[count];
            this.alphaSecond = new double[count];
            this.alphaThird = new double[count];
            for (int i = 0; i < count; i++) {
                double[] coefficients = mathiasCopemanCoefficients[i];
                if (coefficients == null) continue;
                if (coefficients.length != 3 || !Double.isFinite(coefficients[0]) || !Double.isFinite(coefficients[1])
                        || !Double.isFinite(coefficients[2])) {
                    throw new IllegalArgumentException("Mathias-Copeman alpha needs three finite coefficients");
                }
                mathiasCopeman[i] = true;
                alphaFirst[i] = coefficients[0];
                alphaSecond[i] = coefficients[1];
                alphaThird[i] = coefficients[2];
            }
        }
        this.interactions = pairs.interactions();
        this.pairFirst = pairs.first();
        this.pairSecond = pairs.second();
        this.pairConstant = pairs.constant();
        this.pairSlope = pairs.slope();
        this.pairLow = pairs.low();
        this.pairHigh = pairs.high();
        this.pairModel = pairs.model();
        this.pairIndex = new int[count][count];
        for (int[] row : pairIndex) Arrays.fill(row, -1);
        for (int pair = 0; pair < pairFirst.length; pair++) {
            pairIndex[pairFirst[pair]][pairSecond[pair]] = pair;
            pairIndex[pairSecond[pair]][pairFirst[pair]] = pair;
        }
        this.excess = pairs.nrtl() != null;
        int cells = 0;
        if (excess) for (boolean cell : pairs.nrtl()) if (cell) cells++;
        this.cellStart = new int[excess ? count + 1 : 0];
        this.cellPartner = new int[cells];
        this.cellAlpha = new double[cells];
        this.cellIntercept = new double[cells];
        this.cellSlope = new double[cells];
        this.cellLow = new double[cells];
        this.cellHigh = new double[cells];
        this.cellFade = new double[cells];
        this.cellIndex = excess ? new int[count][count] : new int[0][];
        if (excess) {
            int at = 0;
            for (int i = 0; i < count; i++) {
                cellStart[i] = at;
                Arrays.fill(cellIndex[i], -1);
                for (int j = 0; j < count; j++) {
                    int source = i * count + j;
                    if (!pairs.nrtl()[source]) continue;
                    cellIndex[i][j] = at;
                    cellPartner[at] = j;
                    cellAlpha[at] = pairs.alpha()[source];
                    cellIntercept[at] = pairs.intercept()[source];
                    cellLow[at] = pairs.cellLow()[source];
                    cellHigh[at] = pairs.cellHigh()[source];
                    cellFade[at] = pairs.cellFade()[source];
                    cellSlope[at++] = pairs.energySlope()[source];
                }
            }
            cellStart[count] = at;
        }
        this.rankOneMixing = pairFirst.length == 0 && !excess;
        boolean constant = interactions == null;
        for (double slope : pairSlope) constant &= slope == 0.0;
        this.constantPairs = constant;
    }

    public int componentCount() { return count; }
    /** True when the kernel has no interaction at all: the mixture attraction is the rank-one product. */
    public boolean usesRankOneMixing() { return rankOneMixing; }
    /** True when the kernel has an NRTL pair (a polar compound of a derived list). */
    public boolean hasExcessPairs() { return excess; }
    /** {@code 0.07780 R Tc / Pc} for one component; the co-volume the mixture rule sums. */
    public double coVolume(int component) { return coVolumes[component]; }
    /** A workspace that carries the whole list ({@link Workspace#carryAll()}); a caller names its mixture with {@link Workspace#carry}. */
    public Workspace newWorkspace() { return new Workspace(this); }

    /**
     * Tests only: outputs outside a workspace's support are NaN instead of zero, so a reader of a compound the caller
     * did not carry fails loudly, and an amount outside the support is an {@link AssertionError} (no refused-trial
     * handler hides it). Off by default; the system property {@code icheme.kernel.supportNaN} turns it on for a whole test
     * JVM ({@code tools/aqueous-solvents/kernel-cost/support-nan.init.gradle}); a test that turns it on turns it off again.
     */
    public static void fillOutsideSupportWithNaN(boolean on) { outsideSupportNaN = on; }
    /** The value every output outside a workspace's support holds: zero, or NaN under {@link #fillOutsideSupportWithNaN}. */
    public static double outsideSupportFill() { return outsideSupportNaN ? Double.NaN : 0.0; }
    private static boolean outsideSupportNaN = Boolean.getBoolean("icheme.kernel.supportNaN");
    public Evaluation newEvaluation() { return new Evaluation(count); }
    /** A derivative output that carries {@code dh^R/dT} (and so forms the second temperature derivative it reads). */
    public Derivatives newDerivatives() { return new Derivatives(count, true); }
    /**
     * A derivative output without {@code dh^R/dT}: an evaluation skips the second temperature derivative, which nothing
     * else reads (on a kernel with NRTL pairs, all of it; otherwise only the scalar sum), and reading it throws.
     */
    public Derivatives newDerivativesWithoutHeatCapacity() { return new Derivatives(count, false); }

    /** Wilson K initialisation only; accepted states use rigorous fugacity refreshes. */
    public void wilsonK(double temperatureKelvin, double pressurePascal, double[] output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        requireLength(output, "output");
        for (int i = 0; i < count; i++) {
            output[i] = DeterministicMath.exp(Math.clamp(
                    DeterministicMath.log(criticalPressures[i] / pressurePascal)
                            + 5.373 * (1.0 + acentricFactors[i])
                            * (1.0 - criticalTemperatures[i] / temperatureKelvin),
                    -40.0, 40.0));
        }
    }

    public void evaluate(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, composition, root, workspace, output);
    }

    public void prepareTemperature(double temperatureKelvin, Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        if (!Double.isFinite(temperatureKelvin) || temperatureKelvin < minimumTemperatureKelvin
                || temperatureKelvin > maximumTemperatureKelvin) {
            throw new IllegalArgumentException("Temperature is outside package range");
        }
        long bits = Double.doubleToLongBits(temperatureKelvin);
        if (workspace.preparedTemperature == bits) return;
        workspace.requireKernel(this);
        // Only the support's compounds, pairs and cells, each stamped with the temperature it was prepared at: a support
        // that grows at the same temperature prepares only what is new. Each value is the same arithmetic as before.
        boolean roots = excess || (interactions != null && interactions.temperatureDependent());
        int[] support = workspace.support;
        for (int s = 0; s < workspace.supportCount; s++) {
            int i = support[s];
            if (workspace.compoundStamp[i] == bits) continue;
            if (mathiasCopeman != null && mathiasCopeman[i]) {
                prepareMathiasCopeman(i, temperatureKelvin, workspace);
            } else {
                double sqrtTr = Math.sqrt(temperatureKelvin / criticalTemperatures[i]);
                double alphaTerm = 1.0 + kappas[i] * (1.0 - sqrtTr);
                workspace.a[i] = criticalA[i] * alphaTerm * alphaTerm;
                workspace.daDt[i] = -criticalA[i] * kappas[i] * alphaTerm
                        / Math.sqrt(temperatureKelvin * criticalTemperatures[i]);
                workspace.sqrtA[i] = Math.sqrt(workspace.a[i]);
                if (excess) workspace.d2aDt2[i] = soaveSecondDerivative(i, temperatureKelvin);
            }
            if (roots) rootDerivatives(i, temperatureKelvin, workspace, workspace.interactionRootDt, workspace.interactionRootDt2);
            workspace.compoundStamp[i] = bits;
        }
        preparePairs(temperatureKelvin, bits, workspace);
        if (excess) prepareExcess(temperatureKelvin, bits, workspace);
        workspace.preparedTemperature = bits;
    }

    /**
     * {@code a_i = a_c s^2} with {@code s = 1 + c1 u + c2 u^2 + c3 u^3}, {@code u = 1 - sqrt(T/Tc)} (only {@code c1}
     * above {@code Tc}), and its two temperature derivatives from {@code du/dT = -1/(2 sqrt(T Tc))},
     * {@code d^2u/dT^2 = 1/(4 sqrt(T^3 Tc))}: {@code da/dT = 2 a_c s s'}, {@code d^2a/dT^2 = 2 a_c (s'^2 + s s'')}.
     */
    private void prepareMathiasCopeman(int i, double temperatureKelvin, Workspace workspace) {
        double criticalTemperature = criticalTemperatures[i];
        double u = 1.0 - Math.sqrt(temperatureKelvin / criticalTemperature);
        double uDt = -1.0 / (2.0 * Math.sqrt(temperatureKelvin * criticalTemperature));
        double uDt2 = 1.0 / (4.0 * Math.sqrt(temperatureKelvin * temperatureKelvin * temperatureKelvin * criticalTemperature));
        double c1 = alphaFirst[i];
        double c2 = u < 0.0 ? 0.0 : alphaSecond[i];
        double c3 = u < 0.0 ? 0.0 : alphaThird[i];
        double s = 1.0 + u * (c1 + u * (c2 + u * c3));
        double sDu = c1 + u * (2.0 * c2 + 3.0 * c3 * u);
        double sDu2 = 2.0 * c2 + 6.0 * c3 * u;
        double sDt = sDu * uDt;
        double sDt2 = sDu2 * uDt * uDt + sDu * uDt2;
        workspace.a[i] = criticalA[i] * s * s;
        workspace.daDt[i] = 2.0 * criticalA[i] * s * sDt;
        workspace.d2aDt2[i] = 2.0 * criticalA[i] * (sDt * sDt + s * sDt2);
        workspace.sqrtA[i] = Math.sqrt(workspace.a[i]);
    }

    /** {@code d^2a_i/dT^2} of the Soave form: {@code a_c kappa (1 + kappa) / (2 sqrt(T^3 Tc))}. */
    private double soaveSecondDerivative(int i, double temperatureKelvin) {
        return criticalA[i] * kappas[i] * (1.0 + kappas[i])
                / (2.0 * Math.sqrt(temperatureKelvin * temperatureKelvin * temperatureKelvin
                * criticalTemperatures[i]));
    }

    /**
     * {@code k_ij(T)} and its two temperature derivatives per pair of the support's list: a constant or a line (the
     * second derivative zero, a constant's first too, so their added terms are exact zeros), or the model's value, O(pairs)
     * per temperature on the pure-component values just filled. A value that is not finite (the Soave {@code sqrt(a_i)}
     * vanishes at {@code Tr = (1 + 1/kappa)^2}) is refused before the temperature is recorded as prepared, so the
     * workspace never caches it.
     */
    private void preparePairs(double temperatureKelvin, long bits, Workspace workspace) {
        int[] list = workspace.supportPairs;
        for (int s = 0; s < workspace.supportPairCount; s++) {
            int pair = list[s];
            if (workspace.pairStamp[pair] == bits) continue;
            int m = pairModel[pair];
            if (m >= 0) {
                interactions.evaluatePair(m, temperatureKelvin, workspace.sqrtA, workspace.interactionRootDt,
                        workspace.interactionRootDt2, coVolumes, workspace.modelK, workspace.modelKDt, workspace.modelKDt2);
                workspace.pairK[pair] = workspace.modelK[m];
                workspace.pairKDt[pair] = workspace.modelKDt[m];
                workspace.pairKDt2[pair] = workspace.modelKDt2[m];
            } else if (pairSlope[pair] != 0.0) {
                // A line, held past its fitted ends.
                double[] held = workspace.held;
                HuronVidalExcess.held(pairConstant[pair], pairSlope[pair], pairLow[pair], pairHigh[pair], temperatureKelvin, held);
                workspace.pairK[pair] = held[0];
                workspace.pairKDt[pair] = held[1];
                workspace.pairKDt2[pair] = held[2];
            } else {
                workspace.pairK[pair] = pairConstant[pair];
                workspace.pairKDt[pair] = 0.0;
                workspace.pairKDt2[pair] = 0.0;
            }
            if (!Double.isFinite(workspace.pairK[pair]) || !Double.isFinite(workspace.pairKDt[pair])
                    || !Double.isFinite(workspace.pairKDt2[pair])) {
                throw new IllegalArgumentException("Pair interaction of components " + pairFirst[pair] + " and "
                        + pairSecond[pair] + " is not finite at " + temperatureKelvin + " K");
            }
            workspace.pairStamp[pair] = bits;
        }
    }

    /**
     * The temperature-only part of the NRTL cells, per cell (partner {@code j} around centre {@code i}): the line's
     * energy (a line held past its fitted ends, {@link HuronVidalExcess#held}), {@code tau = dg/(R T)},
     * {@code tau' = dg'/(R T) - tau/T}, {@code tau'' = dg''/(R T) - 2 tau'/T},
     * {@code G = exp(-alpha tau)}, {@code G' = -alpha tau' G}, {@code G'' = alpha (alpha tau'^2 - tau'') G}; and the
     * cell's classical energy, the one conversion of a classical pair into Huron-Vidal energies ({@link #classicalTau}
     * with {@code k = 0}). The cell keeps its correction to the quadratic rule, {@code F = G - 1} and
     * {@code R = G tau - tau_cl}, each with its two temperature derivatives, and {@code G} and {@code G tau} themselves
     * with the derivatives of {@code G tau}, which an active centre's sums read directly ({@link #correctExcess}: near
     * {@code G = 0}, {@code F + 1} and {@code R + tau_cl} have lost them). A value that is not finite is refused before
     * the temperature is recorded as prepared.
     */
    private void prepareExcess(double temperatureKelvin, long bits, Workspace workspace) {
        double[] s = workspace.sqrtA;
        double[] sDt = workspace.interactionRootDt;
        double[] sDt2 = workspace.interactionRootDt2;
        double lambda = HuronVidalExcess.LAMBDA;
        double rt = GAS_CONSTANT * temperatureKelvin;
        int[] support = workspace.support;
        int[] cells = workspace.supportCells;
        for (int si = 0; si < workspace.supportCount; si++) {
            int i = support[si];
            double bi = coVolumes[i];
            for (int at = workspace.cellFrom[i]; at < workspace.cellTo[i]; at++) {
                int c = cells[at];
                if (workspace.cellStamp[c] == bits) continue;
                int j = cellPartner[c];
                double bj = coVolumes[j];
                double[] held = workspace.held;
                HuronVidalExcess.held(cellIntercept[c], cellSlope[c], cellLow[c], cellHigh[c], cellFade[c], temperatureKelvin, held);
                double tau = held[0] / rt;
                double tauDt = held[1] / rt - tau / temperatureKelvin;
                double tauDt2 = held[2] / rt - 2.0 * tauDt / temperatureKelvin;
                double alpha = cellAlpha[c];
                double g = DeterministicMath.exp(-alpha * tau);
                double gDt = -alpha * tauDt * g;
                double gDt2 = alpha * (alpha * tauDt * tauDt - tauDt2) * g;
                // The classical energy of the cell, e = Lambda (a_i/b_i - s_i s_j/b_j), and its two derivatives.
                double energy = lambda * (workspace.a[i] / bi - s[i] * s[j] / bj);
                double energyDt = lambda * (workspace.daDt[i] / bi - (sDt[i] * s[j] + s[i] * sDt[j]) / bj);
                double energyDt2 = lambda * (workspace.d2aDt2[i] / bi
                        - (sDt2[i] * s[j] + 2.0 * sDt[i] * sDt[j] + s[i] * sDt2[j]) / bj);
                double classical = energy / rt;
                double classicalDt = energyDt / rt - classical / temperatureKelvin;
                double classicalDt2 = energyDt2 / rt - 2.0 * classicalDt / temperatureKelvin;
                double r = g * tau - classical;
                double rDt = gDt * tau + g * tauDt - classicalDt;
                double rDt2 = gDt2 * tau + 2.0 * gDt * tauDt + g * tauDt2 - classicalDt2;
                if (!Double.isFinite(g) || !Double.isFinite(gDt) || !Double.isFinite(gDt2)
                        || !Double.isFinite(r) || !Double.isFinite(rDt) || !Double.isFinite(rDt2)) {
                    throw new IllegalArgumentException("Excess energy of component " + j + " around " + i
                            + " is not finite at " + temperatureKelvin + " K");
                }
                workspace.cellF[c] = g - 1.0;
                workspace.cellFDt[c] = gDt;
                workspace.cellFDt2[c] = gDt2;
                workspace.cellR[c] = r;
                workspace.cellRDt[c] = rDt;
                workspace.cellRDt2[c] = rDt2;
                workspace.cellG[c] = g;
                workspace.cellGTau[c] = g * tau;
                workspace.cellGTauDt[c] = gDt * tau + g * tauDt;
                workspace.cellGTauDt2[c] = gDt2 * tau + 2.0 * gDt * tauDt + g * tauDt2;
                workspace.cellStamp[c] = bits;
            }
        }
    }

    /**
     * The conversion of a classical pair into the Huron-Vidal energy of {@code j} around a centre {@code i}, divided by
     * {@code R T} (W3b design N8, option B): {@code tau_ji = Lambda [a_i/b_i - (1 - k_ij) s_i s_j/b_j]/(R T)}. Weighted
     * by {@code b_j x_j} and summed over {@code j} it is {@code Lambda [b a_i/b_i - s_i g_i]/(R T)} with the quadratic
     * row {@code g_i = sum_j (1 - k_ij) x_j s_j}, so with every pair classical the rule is the quadratic one exactly.
     * Every classical term below is this expression or one of its sums; the NRTL cells store their difference to it.
     */
    private double classicalTau(int i, int j, double inverseRt, Workspace workspace) {
        int pair = pairIndex[i][j];
        return HuronVidalExcess.LAMBDA * inverseRt * (workspace.a[i] / coVolumes[i]
                - (1.0 - workspace.interaction(pair)) * workspace.sqrtA[i] * workspace.sqrtA[j] / coVolumes[j]);
    }

    private void evaluatePrepared(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(output, "output");
        normalizeInto(composition, workspace);
        // Every sum below runs over the support in ascending order: the compounds it leaves out have no amount, so
        // their terms are exact zeros and each value on the support keeps its bits against the whole-list sum.
        int[] support = workspace.support;
        int n = workspace.supportCount;
        int[] pairs = workspace.supportPairs;
        int pairCount = workspace.supportPairCount;
        double bMix = 0.0;
        for (int s = 0; s < n; s++) {
            int i = support[s];
            bMix += workspace.composition[i] * coVolumes[i];
            workspace.q[i] = workspace.sqrtA[i] * workspace.composition[i];
        }
        double aMix;
        double daMixDt;
        if (rankOneMixing) {
            double qSum = 0.0;
            double dqSum = 0.0;
            for (int s = 0; s < n; s++) {
                int i = support[s];
                qSum += workspace.q[i];
                dqSum += workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
            }
            aMix = qSum * qSum;
            daMixDt = 2.0 * qSum * dqSum;
            for (int s = 0; s < n; s++) workspace.sumA[support[s]] = workspace.sqrtA[support[s]] * qSum;
        } else {
            // g_i = sum_j (1 - k_ij) q_j, reached as the rank-one sum minus the pairs that are not zero, on the
            // prepared k_ij(T); with NRTL cells, in the same pass over the pairs, the row's temperature derivative
            // g_i' = sum_j (1 - k_ij) x_j s_j' - sum_j k_ij' x_j s_j that correctExcess reads, and the interactions'
            // own d/dT of -sum_ij q_i q_j k_ij at fixed q, -2 sum_pairs q_i q_j dk_ij/dT (exact zeros for constant
            // pairs, so a constant list skips them). One pass, each sum in the order the separate passes took.
            double[] k = workspace.pairK;
            double[] kDt = workspace.pairKDt;
            double[] q = workspace.q;
            double[] g = workspace.g;
            double qSum = 0.0;
            for (int s = 0; s < n; s++) qSum += q[support[s]];
            for (int s = 0; s < n; s++) g[support[s]] = qSum;
            double interactionDt = 0.0;
            if (excess) {
                double[] x = workspace.composition;
                double[] sDt = workspace.interactionRootDt;
                double[] gDt = workspace.rowGDt;
                double rootSum = 0.0;
                for (int s = 0; s < n; s++) rootSum += x[support[s]] * sDt[support[s]];
                for (int s = 0; s < n; s++) gDt[support[s]] = rootSum;
                if (constantPairs) {
                    for (int p = 0; p < pairCount; p++) {
                        int pair = pairs[p];
                        int i = pairFirst[pair];
                        int j = pairSecond[pair];
                        double interaction = k[pair];
                        g[i] -= interaction * q[j];
                        g[j] -= interaction * q[i];
                        gDt[i] -= interaction * x[j] * sDt[j];
                        gDt[j] -= interaction * x[i] * sDt[i];
                    }
                } else {
                    double[] s = workspace.sqrtA;
                    for (int p = 0; p < pairCount; p++) {
                        int pair = pairs[p];
                        int i = pairFirst[pair];
                        int j = pairSecond[pair];
                        double interaction = k[pair];
                        double slope = kDt[pair];
                        g[i] -= interaction * q[j];
                        g[j] -= interaction * q[i];
                        gDt[i] -= interaction * x[j] * sDt[j] + slope * x[j] * s[j];
                        gDt[j] -= interaction * x[i] * sDt[i] + slope * x[i] * s[i];
                        interactionDt += slope * q[i] * q[j];
                    }
                }
            } else if (constantPairs) {
                for (int p = 0; p < pairCount; p++) {
                    int pair = pairs[p];
                    int i = pairFirst[pair];
                    int j = pairSecond[pair];
                    double interaction = k[pair];
                    g[i] -= interaction * q[j];
                    g[j] -= interaction * q[i];
                }
            } else {
                for (int p = 0; p < pairCount; p++) {
                    int pair = pairs[p];
                    int i = pairFirst[pair];
                    int j = pairSecond[pair];
                    double interaction = k[pair];
                    g[i] -= interaction * q[j];
                    g[j] -= interaction * q[i];
                    interactionDt += kDt[pair] * q[i] * q[j];
                }
            }
            aMix = 0.0;
            daMixDt = 0.0;
            for (int s = 0; s < n; s++) {
                int i = support[s];
                workspace.sumA[i] = workspace.sqrtA[i] * workspace.g[i];
                aMix += workspace.q[i] * workspace.g[i];
                double dq = workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
                daMixDt += 2.0 * dq * workspace.g[i];
            }
            daMixDt -= 2.0 * interactionDt;
        }
        if (excess) {
            // The NRTL cells' correction: a = a_quadratic - (R T/Lambda) b dE, sumA_i -= (R T/(2 Lambda))(b_i dE + b
            // dln gamma_i); exact zeros where no NRTL partner has an amount, so such a phase keeps the quadratic values.
            correctExcess(temperatureKelvin, workspace, bMix);
            double scale = GAS_CONSTANT / HuronVidalExcess.LAMBDA;
            aMix -= scale * temperatureKelvin * bMix * workspace.excessEnergy;
            daMixDt -= scale * bMix * (workspace.excessEnergy + temperatureKelvin * workspace.excessEnergyDt);
        }
        double reducedA = aMix * pressurePascal / (GAS_CONSTANT * GAS_CONSTANT * temperatureKelvin * temperatureKelvin);
        double reducedB = bMix * pressurePascal / (GAS_CONSTANT * temperatureKelvin);
        RootSelection rootSelection = selectRoot(reducedA, reducedB, root);
        double z = rootSelection.selectedCompressibility();
        double logRatio = DeterministicMath.log((z + (1.0 + SQRT_TWO) * reducedB) / (z + (1.0 - SQRT_TWO) * reducedB));
        double attraction = reducedA / (2.0 * SQRT_TWO * Math.max(reducedB, 1.0e-300));
        // Hoisted, not changed: the argument does not depend on i, so this is the same double the loop
        // used to recompute n times, and one logarithm instead of one per component.
        double freeVolumeLog = DeterministicMath.log(z - reducedB);
        for (int s = 0; s < n; s++) {
            int i = support[s];
            double bRatio = coVolumes[i] / bMix;
            double attractionRatio = 2.0 * workspace.sumA[i] / aMix - bRatio;
            output.logFugacityCoefficients[i] = bRatio * (z - 1.0) - freeVolumeLog
                    - attraction * attractionRatio * logRatio;
        }
        if (output.outsideStale(workspace)) {
            double fill = outsideSupportNaN ? Double.NaN : 0.0;
            boolean[] carried = workspace.inSupport;
            for (int i = 0; i < count; i++) if (!carried[i]) output.logFugacityCoefficients[i] = fill;
            output.markOutside(workspace, outsideSupportNaN);
        }
        output.compressibility = z;
        output.residualEnthalpyJoulesPerMol = GAS_CONSTANT * temperatureKelvin * (z - 1.0)
                + (temperatureKelvin * daMixDt - aMix) / (2.0 * SQRT_TWO * bMix) * logRatio;
        output.aMix = aMix;
        output.bMix = bMix;
        // Recorded, not recomputed: the derivative evaluation below needs exactly this mixture da/dT, and
        // recomputing it there would be a second arithmetic path for a number this one already has.
        output.daMixDt = daMixDt;
        output.physicalRootCount = rootSelection.physicalRootCount();
        output.rootSeparation = rootSelection.rootSeparation();
    }

    /**
     * Fills {@code output} with the value evaluation and its first derivatives on the very same root: the values come from
     * {@link #evaluatePrepared} itself, and everything after reads the mixture terms that evaluation left in the
     * workspace. {@code Z} is a root of the fixed cubic {@code Z^3 + c2 Z^2 + c1 Z + c0} whose coefficients are
     * polynomials in {@code (A, B)}, so the implicit function theorem gives {@code dZ/dA} and {@code dZ/dB} from one
     * derivative of that cubic, {@code 3Z^2 + 2 c2 Z + c1}, which is also the quantity that vanishes when two roots
     * coalesce: the guard refuses such a state instead of returning the enormous number the division would give. The
     * partial molar residual enthalpies are {@code -R T^2 (d ln phi_i / dT)}, and their mixture sum reproducing
     * {@code H^R} is a check of the temperature derivative against the closed-form residual enthalpy.
     */
    public void evaluateDerivatives(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Derivatives output) {
        Objects.requireNonNull(output, "output");
        prepareTemperature(temperatureKelvin, workspace);
        evaluatePrepared(temperatureKelvin, pressurePascal, composition, root, workspace, output.evaluation);
        double a = output.evaluation.aMix;
        double b = output.evaluation.bMix;
        double daDt = output.evaluation.daMixDt;
        double z = output.evaluation.compressibility;
        double reducedA = a * pressurePascal / (GAS_CONSTANT * GAS_CONSTANT * temperatureKelvin * temperatureKelvin);
        double reducedB = b * pressurePascal / (GAS_CONSTANT * temperatureKelvin);
        double c2 = -(1.0 - reducedB);
        double c1 = reducedA - 3.0 * reducedB * reducedB - 2.0 * reducedB;
        double slope = Math.fma(Math.fma(3.0, z, 2.0 * c2), z, c1);
        double slopeScale = 3.0 * z * z + 2.0 * Math.abs(c2) * Math.abs(z) + Math.abs(c1);
        if (!Double.isFinite(slope) || Math.abs(slope) <= COALESCENCE_SLOPE_TOLERANCE * Math.max(1.0, slopeScale)) {
            throw new IllegalStateException("Peng-Robinson " + root + " root is too close to coalescence to differentiate");
        }
        double zByReducedA = -(z - reducedB) / slope;
        double zByReducedB = -(z * z - (6.0 * reducedB + 2.0) * z - reducedA + 2.0 * reducedB
                + 3.0 * reducedB * reducedB) / slope;
        double lower = z + (1.0 + SQRT_TWO) * reducedB;
        double upper = z + (1.0 - SQRT_TWO) * reducedB;
        double logRatio = DeterministicMath.log(lower / upper);
        double attraction = reducedA / (2.0 * SQRT_TWO * Math.max(reducedB, 1.0e-300));
        boolean exponential = excess;
        // d^2a/dT^2 is read only by dh^R/dT; the root and cross vectors it fills only by the quadratic rows' dS_i/dT.
        double secondDaDt = output.heatCapacity || !exponential
                ? secondTemperatureDerivative(temperatureKelvin, workspace, output.rootDt, output.rootDt2, output.crossDt)
                : Double.NaN;
        if (exponential) differentiateExcess(temperatureKelvin, workspace);
        double reducedADt = reducedA * (daDt / a - 2.0 / temperatureKelvin);
        double reducedBDt = -reducedB / temperatureKelvin;
        double zDt = zByReducedA * reducedADt + zByReducedB * reducedBDt;
        double logRatioDt = (zDt + (1.0 + SQRT_TWO) * reducedBDt) / lower - (zDt + (1.0 - SQRT_TWO) * reducedBDt) / upper;
        double attractionDt = attraction * (reducedADt / reducedA - reducedBDt / reducedB);
        int[] support = workspace.support;
        int n = workspace.supportCount;
        for (int s = 0; s < n; s++) {
            int i = support[s];
            output.coVolumeRatio[i] = coVolumes[i] / b;
            output.attractionRatio[i] = 2.0 * workspace.sumA[i] / a - output.coVolumeRatio[i];
            double sumADt = exponential ? workspace.excessRowDt[i]
                    : output.rootDt[i] * (workspace.sumA[i] / workspace.sqrtA[i])
                    + workspace.sqrtA[i] * output.crossDt[i];
            double attractionRatioDt = 2.0 * (sumADt * a - workspace.sumA[i] * daDt) / (a * a);
            output.dLogPhiDt[i] = output.coVolumeRatio[i] * zDt - (zDt - reducedBDt) / (z - reducedB)
                    - (attractionDt * output.attractionRatio[i] * logRatio
                    + attraction * attractionRatioDt * logRatio
                    + attraction * output.attractionRatio[i] * logRatioDt);
        }
        for (int sj = 0; sj < n; sj++) {
            int j = support[sj];
            double reducedAdn = 2.0 * reducedA * (workspace.sumA[j] - a) / a;
            double coVolumeShift = (coVolumes[j] - b) / b;
            double reducedBdn = reducedB * coVolumeShift;
            double zDn = zByReducedA * reducedAdn + zByReducedB * reducedBdn;
            double logRatioDn = (zDn + (1.0 + SQRT_TWO) * reducedBdn) / lower - (zDn + (1.0 - SQRT_TWO) * reducedBdn) / upper;
            double attractionDn = attraction * (reducedAdn / reducedA - reducedBdn / reducedB);
            double sharedTerms = -(zDn - reducedBdn) / (z - reducedB);
            double attractionShift = 2.0 * (workspace.sumA[j] - a);
            double rootAj = workspace.sqrtA[j];
            int[] pairsOfJ = pairIndex[j];
            for (int si = 0; si < n; si++) {
                int i = support[si];
                double bRatioDn = -output.coVolumeRatio[i] * coVolumeShift;
                // A composition derivative at fixed T: k_ij(T) enters by value only. crossA_ij is
                // (1/2) d^2(n^2 a)/dn_i dn_j, which is a_ij = (1 - k_ij) sqrt(a_i a_j) for the quadratic part.
                double crossA = exponential ? workspace.excessCross[i * count + j]
                        : (1.0 - workspace.interaction(pairsOfJ[i])) * workspace.sqrtA[i] * rootAj;
                double attractionRatioDn = 2.0 * ((crossA - workspace.sumA[i]) * a
                        - workspace.sumA[i] * attractionShift) / (a * a) - bRatioDn;
                output.dLogPhiDn[i][j] = bRatioDn * (z - 1.0) + output.coVolumeRatio[i] * zDn + sharedTerms
                        - (attractionDn * output.attractionRatio[i] * logRatio
                        + attraction * attractionRatioDn * logRatio
                        + attraction * output.attractionRatio[i] * logRatioDn);
            }
        }
        output.dResidualEnthalpyDt = !output.heatCapacity ? Double.NaN
                : GAS_CONSTANT * (z - 1.0) + GAS_CONSTANT * temperatureKelvin * zDt
                + temperatureKelvin * secondDaDt / (2.0 * SQRT_TWO * b) * logRatio
                + (temperatureKelvin * daDt - a) / (2.0 * SQRT_TWO * b) * logRatioDt;
        for (int s = 0; s < n; s++) {
            int i = support[s];
            output.partialMolarResidualEnthalpy[i] =
                    -GAS_CONSTANT * temperatureKelvin * temperatureKelvin * output.dLogPhiDt[i];
        }
        if (output.outsideStale(workspace)) fillOutside(output, workspace);
    }

    /**
     * Zeros (NaN under {@link #fillOutsideSupportWithNaN}) in every derivative entry with an index outside the support:
     * the rows and columns of the dn block, {@code d ln phi/dT} and the partial enthalpies. Run only when the output was
     * last filled on another support, so a fixed support pays it once.
     */
    private void fillOutside(Derivatives output, Workspace workspace) {
        double fill = outsideSupportNaN ? Double.NaN : 0.0;
        boolean[] carried = workspace.inSupport;
        for (int i = 0; i < count; i++) {
            double[] row = output.dLogPhiDn[i];
            if (!carried[i]) {
                output.dLogPhiDt[i] = fill;
                output.partialMolarResidualEnthalpy[i] = fill;
                Arrays.fill(row, fill);
                continue;
            }
            for (int j = 0; j < count; j++) if (!carried[j]) row[j] = fill;
        }
        output.markOutside(workspace, outsideSupportNaN);
    }

    /**
     * {@code d^2 a_mix / dT^2} of the mixture the preceding evaluation left in {@code workspace}, filling the
     * three caller-owned scratch vectors it needs on the way: {@code d sqrt(a_i)/dT}, its own derivative, and
     * {@code d g_i/dT} of {@code g_i = S_i/sqrt(a_i) = sum_j (1-k_ij) x_j sqrt(a_j)}, with the pairs' own {@code dk_ij/dT}.
     *
     * <p>One expression, two callers: {@link #evaluateDerivatives} needs the two root vectors and the cross
     * sum again for {@code d ln phi_i/dT}, and the network's volumetric block needs only the scalar. Splitting
     * it would give the same number two arithmetic paths.</p>
     *
     * <p>On a kernel with NRTL pairs the quadratic part is formed the same way and the cells' correction is added
     * ({@link #excessSecondTemperatureDerivative}); {@link #evaluateDerivatives} then reads the corrected rows'
     * derivatives from the workspace.</p>
     */
    public double secondTemperatureDerivative(
            double temperatureKelvin, Workspace workspace, double[] rootDt, double[] rootDt2, double[] crossDt) {
        prepareRootDerivatives(temperatureKelvin, workspace, rootDt, rootDt2);
        return mixtureSecondTemperatureDerivative(workspace, rootDt, rootDt2, crossDt);
    }

    /**
     * The temperature-only half of {@link #secondTemperatureDerivative}: {@code d sqrt(a_i)/dT} and its own
     * derivative, from which every mixture temperature derivative follows. A caller that holds a prepared
     * temperature fills these once with it and calls
     * {@link #mixtureSecondTemperatureDerivative} per composition.
     */
    public void prepareRootDerivatives(
            double temperatureKelvin, Workspace workspace, double[] rootDt, double[] rootDt2) {
        int[] support = workspace.support;
        for (int s = 0; s < workspace.supportCount; s++) rootDerivatives(support[s], temperatureKelvin, workspace, rootDt, rootDt2);
    }

    /** One compound of {@link #prepareRootDerivatives}. */
    private void rootDerivatives(int i, double temperatureKelvin, Workspace workspace, double[] rootDt, double[] rootDt2) {
        double secondADt = mathiasCopeman != null && mathiasCopeman[i]
                ? workspace.d2aDt2[i] : soaveSecondDerivative(i, temperatureKelvin);
        rootDt[i] = workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
        rootDt2[i] = secondADt / (2.0 * workspace.sqrtA[i])
                - workspace.daDt[i] * workspace.daDt[i]
                / (4.0 * workspace.sqrtA[i] * workspace.sqrtA[i] * workspace.sqrtA[i]);
    }

    /**
     * The composition-dependent half, on the root derivatives {@link #prepareRootDerivatives} filled. On a kernel with NRTL pairs
     * the cells' correction of the last evaluation is added to the quadratic part.
     */
    public double mixtureSecondTemperatureDerivative(
            Workspace workspace, double[] rootDt, double[] rootDt2, double[] crossDt) {
        double[] x = workspace.composition;
        int[] support = workspace.support;
        int n = workspace.supportCount;
        int[] pairs = workspace.supportPairs;
        double interactionSum = 0.0;
        for (int s = 0; s < n; s++) interactionSum += x[support[s]] * rootDt[support[s]];
        for (int s = 0; s < n; s++) crossDt[support[s]] = interactionSum;
        double[] k = workspace.pairK;
        for (int p = 0; p < workspace.supportPairCount; p++) {
            int pair = pairs[p];
            int i = pairFirst[pair];
            int j = pairSecond[pair];
            double interaction = k[pair];
            crossDt[i] -= interaction * x[j] * rootDt[j];
            crossDt[j] -= interaction * x[i] * rootDt[i];
        }
        double secondDaDt = 0.0;
        for (int s = 0; s < n; s++) {
            int i = support[s];
            // The quadratic row g_i; where the cells corrected the rows, sumA_i is no longer s_i g_i.
            double row = excess ? workspace.g[i] : workspace.sumA[i] / workspace.sqrtA[i];
            secondDaDt += 2.0 * x[i] * (rootDt2[i] * row + rootDt[i] * crossDt[i]);
        }
        if (pairFirst.length > 0 && !constantPairs) secondDaDt = addInteractionTemperatureTerms(workspace, rootDt, crossDt, secondDaDt);
        if (excess) secondDaDt += excessSecondTemperatureDerivative(workspace);
        return secondDaDt;
    }

    /**
     * The terms the pairs' own temperature dependence adds once the constant-interaction sums above are formed,
     * with {@code q_i = x_i sqrt(a_i)} and {@code dq_i = x_i d sqrt(a_i)/dT}:
     * {@code d^2a_mix/dT^2 -= 4 sum_pairs (dq_i q_j + q_i dq_j) dk_ij/dT + 2 sum_pairs q_i q_j d^2k_ij/dT^2}, and
     * {@code crossDt_i -= sum_(j paired with i) q_j dk_ij/dT}, which makes {@code crossDt_i} the whole
     * {@code d g_i/dT} of {@code g_i = S_i/sqrt(a_i)}, so {@link #evaluateDerivatives}'s
     * {@code dS_i/dT = (d sqrt(a_i)/dT) g_i + sqrt(a_i) crossDt_i} carries the interactions' own derivative. For a
     * constant pair every added term is an exact zero.
     */
    private double addInteractionTemperatureTerms(
            Workspace workspace, double[] rootDt, double[] crossDt, double secondDaDt) {
        double[] x = workspace.composition;
        double[] q = workspace.q;
        double firstOrder = 0.0;
        double secondOrder = 0.0;
        int[] pairs = workspace.supportPairs;
        for (int p = 0; p < workspace.supportPairCount; p++) {
            int pair = pairs[p];
            int i = pairFirst[pair];
            int j = pairSecond[pair];
            double kDt = workspace.pairKDt[pair];
            firstOrder += kDt * (x[i] * rootDt[i] * q[j] + q[i] * x[j] * rootDt[j]);
            secondOrder += workspace.pairKDt2[pair] * q[i] * q[j];
            crossDt[i] -= kDt * q[j];
            crossDt[j] -= kDt * q[i];
        }
        return secondDaDt - (4.0 * firstOrder + 2.0 * secondOrder);
    }


    /**
     * The NRTL cells' correction to the quadratic rule for the composition just normalised into the workspace, on its
     * quadratic rows {@code g_i}. With {@code w_j = x_j b_j}, the classical baseline of a centre {@code i} is
     * {@code D_i = b}, {@code theta_i^b = Lambda (a_i/b_i - s_i g_i/b)/(R T)} ({@link #classicalTau} summed). A centre
     * whose cells have a partner with an amount (an active centre) has {@code D_i = B_i + sum_j w_j G_ji} and
     * {@code theta_i = [Lambda (a_i/b_i B_i - s_i g^r_i)/(R T) + sum_j w_j G_ji tau_ji]/D_i}, its classical part
     * {@code B_i = sum_j w_j} and {@code g^r_i = sum_j (1 - k_ij) x_j s_j} formed directly over the compounds that are
     * not its partners ({@code O(n)} per active centre). As {@code b} plus the cells' {@code w_j (G_ji - 1)} the sums
     * cancel where the partners hold nearly all of {@code b} and {@code G} is small: water around the heaviest crude cut,
     * {@code G} 9e-16 at 298 K, left the cut's ln phi in pure water as rounding noise (W3B_NETWORK_PLAN.md section 11).
     * A centre with no such partner keeps its baseline exactly. Then {@code dE = sum_i x_i (theta_i - theta_i^b)} and, per compound {@code k},
     * {@code dln gamma_k = (theta_k - theta_k^b) + b_k sum_i x_i [tau_ki^cl w_i - (theta_i/D_i - theta_i^b/b)]
     * + b_k sum_i x_i R'_ki/D_i}, with {@code w_i = 1/D_i - 1/b} and {@code R'_ki = R_ki - F_ki theta_i} on the cells
     * of present centres. The middle sum is separable: {@code Lambda/(R T) [Z1 - (s_k/b_k) Y_k] - Z2}, with
     * {@code Y_k = sum_i (1 - k_ik) x_i s_i w_i}, reached as the full sum minus the pairs. The cost is
     * {@code O(n + pairs + cells)}.
     */
    private void correctExcess(double temperatureKelvin, Workspace workspace, double bMix) {
        double[] x = workspace.composition;
        double[] s = workspace.sqrtA;
        double[] sDt = workspace.interactionRootDt;
        double[] g = workspace.g;
        double[] gDt = workspace.rowGDt;
        double inverseRt = 1.0 / (GAS_CONSTANT * temperatureKelvin);
        double lambda = HuronVidalExcess.LAMBDA;
        double lambdaOverR = lambda / GAS_CONSTANT;
        double share = lambda * inverseRt;
        workspace.excessTemperature = temperatureKelvin;
        workspace.excessCoVolume = bMix;
        // gDt, d g_i/dT with the pairs' dk/dT, was formed in evaluatePrepared's pass over the pairs.
        double excessEnergy = 0.0;
        double excessEnergyDt = 0.0;
        boolean activePresent = false;
        int[] support = workspace.support;
        int n = workspace.supportCount;
        int[] cells = workspace.supportCells;
        for (int si = 0; si < n; si++) {
            int i = support[si];
            double u = workspace.a[i] / coVolumes[i] - s[i] * g[i] / bMix;
            double uDt = workspace.daDt[i] / coVolumes[i] - (sDt[i] * g[i] + s[i] * gDt[i]) / bMix;
            double base = share * u;
            double baseDt = lambdaOverR * (uDt / temperatureKelvin - u / (temperatureKelvin * temperatureKelvin));
            workspace.thetaBase[i] = base;
            workspace.thetaBaseDt[i] = baseDt;
            double cellD = 0.0, dDDt = 0.0, dDDt2 = 0.0, cellC = 0.0, cellCDt = 0.0, cellCDt2 = 0.0;
            boolean active = false;
            for (int at = workspace.cellFrom[i]; at < workspace.cellTo[i]; at++) {
                int c = cells[at];
                int j = cellPartner[c];
                double weight = x[j] * coVolumes[j];
                if (weight == 0.0) continue;
                active = true;
                cellD += weight * workspace.cellG[c];
                dDDt += weight * workspace.cellFDt[c];
                dDDt2 += weight * workspace.cellFDt2[c];
                cellC += weight * workspace.cellGTau[c];
                cellCDt += weight * workspace.cellGTauDt[c];
                cellCDt2 += weight * workspace.cellGTauDt2[c];
            }
            workspace.active[i] = active;
            if (!active) {
                workspace.denominator[i] = bMix;
                workspace.denominatorDt[i] = 0.0;
                workspace.denominatorDt2[i] = 0.0;
                workspace.numeratorDt2[i] = 0.0;
                workspace.theta[i] = base;
                workspace.thetaDt[i] = baseDt;
                workspace.omega[i] = 0.0;
                continue;
            }
            // The classical part over the non-partners, directly: never b less the partners' weights.
            double restB = 0.0, restG = 0.0, restGDt = 0.0;
            int[] cellsOfI = cellIndex[i];
            int[] pairsOfI = pairIndex[i];
            for (int sj = 0; sj < n; sj++) {
                int j = support[sj];
                if (x[j] == 0.0 || cellsOfI[j] >= 0) continue;
                int pair = pairsOfI[j];
                double k = pair < 0 ? 0.0 : workspace.pairK[pair];
                double kDt = pair < 0 ? 0.0 : workspace.pairKDt[pair];
                restB += x[j] * coVolumes[j];
                restG += (1.0 - k) * x[j] * s[j];
                restGDt += (1.0 - k) * x[j] * sDt[j] - kDt * x[j] * s[j];
            }
            double rest = workspace.a[i] / coVolumes[i] * restB - s[i] * restG;
            double restDt = workspace.daDt[i] / coVolumes[i] * restB - (sDt[i] * restG + s[i] * restGDt);
            double denominator = restB + cellD;
            double theta = (share * rest + cellC) / denominator;
            double numeratorDt = lambdaOverR * (restDt / temperatureKelvin - rest / (temperatureKelvin * temperatureKelvin)) + cellCDt;
            double thetaDt = (numeratorDt - theta * dDDt) / denominator;
            workspace.restCoVolume[i] = restB;
            workspace.restRow[i] = restG;
            workspace.restRowDt[i] = restGDt;
            workspace.denominator[i] = denominator;
            workspace.denominatorDt[i] = dDDt;
            workspace.denominatorDt2[i] = dDDt2;
            workspace.numeratorDt2[i] = cellCDt2;
            workspace.theta[i] = theta;
            workspace.thetaDt[i] = thetaDt;
            workspace.omega[i] = 1.0 / denominator - 1.0 / bMix;
            if (x[i] > 0.0) {
                activePresent = true;
                excessEnergy += x[i] * (theta - base);
                excessEnergyDt += x[i] * (thetaDt - baseDt);
            }
        }
        workspace.excessEnergy = excessEnergy;
        workspace.excessEnergyDt = excessEnergyDt;
        workspace.activePresent = activePresent;
        double[] logGamma = workspace.deltaLogGamma;
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            logGamma[k] = workspace.active[k] ? workspace.theta[k] - workspace.thetaBase[k] : 0.0;
        }
        int[] pairs = workspace.supportPairs;
        int pairCount = workspace.supportPairCount;
        if (activePresent) {
            double z1 = 0.0, z2 = 0.0, ySum = 0.0;
            for (int si = 0; si < n; si++) {
                int i = support[si];
                if (!workspace.active[i] || x[i] == 0.0) continue;
                double omega = workspace.omega[i];
                z1 += x[i] * workspace.a[i] / coVolumes[i] * omega;
                ySum += x[i] * s[i] * omega;
                z2 += x[i] * (workspace.theta[i] / workspace.denominator[i] - workspace.thetaBase[i] / bMix);
            }
            double[] y = workspace.rowY;
            for (int sk = 0; sk < n; sk++) y[support[sk]] = ySum;
            for (int p = 0; p < pairCount; p++) {
                int pair = pairs[p];
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double k = workspace.pairK[pair];
                y[i] -= k * x[j] * s[j] * presentOmega(j, workspace);
                y[j] -= k * x[i] * s[i] * presentOmega(i, workspace);
            }
            workspace.excessZ1 = z1;
            workspace.excessZ2 = z2;
            for (int sk = 0; sk < n; sk++) {
                int k = support[sk];
                logGamma[k] += coVolumes[k] * share * z1 - share * s[k] * y[k] - coVolumes[k] * z2;
            }
        }
        for (int si = 0; si < n; si++) {
            int i = support[si];
            if (x[i] == 0.0) continue;
            double theta = workspace.theta[i];
            double scale = x[i] / workspace.denominator[i];
            for (int at = workspace.cellFrom[i]; at < workspace.cellTo[i]; at++) {
                int c = cells[at];
                int k = cellPartner[c];
                logGamma[k] += scale * coVolumes[k] * (workspace.cellR[c] - workspace.cellF[c] * theta);
            }
        }
        double half = GAS_CONSTANT * temperatureKelvin / (2.0 * lambda);
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            workspace.sumA[k] -= half * (coVolumes[k] * excessEnergy + bMix * logGamma[k]);
        }
    }

    /** {@code w_i = 1/D_i - 1/b} of a centre that has an amount; zero otherwise (its classical sums are the baseline's). */
    private static double presentOmega(int i, Workspace workspace) {
        return workspace.composition[i] == 0.0 ? 0.0 : workspace.omega[i];
    }

    /**
     * {@code d^2 a/dT^2} of the correction for the composition {@link #correctExcess} last formed:
     * {@code -(R/Lambda) b (2 dE' + T dE'')}, {@code dE'' = sum_i x_i (theta_i'' - theta_i^b'')} over the present centres
     * with a present NRTL partner; {@code theta'' = (C'' - 2 theta' D' - theta D'')/D}, {@code C''} the second derivative
     * of the numerator as correctExcess formed it (the classical part over the non-partners, directly, and the cells'
     * {@code w_j (G tau)''}),
     * {@code theta^b'' = (Lambda/R)(u''/T - 2u'/T^2 + 2u/T^3)}, {@code u = a_i/b_i - s_i g_i/b}, on the quadratic row's
     * {@code g_i''} with the pairs' {@code dk/dT} and {@code d^2k/dT^2}. Zero where no such centre is present.
     */
    private double excessSecondTemperatureDerivative(Workspace workspace) {
        if (!workspace.activePresent) return 0.0;
        double[] x = workspace.composition;
        double[] s = workspace.sqrtA;
        double[] sDt = workspace.interactionRootDt;
        double[] sDt2 = workspace.interactionRootDt2;
        double[] g = workspace.g;
        double[] gDt = workspace.rowGDt;
        double[] gDt2 = workspace.rowGDt2;
        double temperature = workspace.excessTemperature;
        double bMix = workspace.excessCoVolume;
        int[] support = workspace.support;
        int n = workspace.supportCount;
        int[] pairs = workspace.supportPairs;
        double rootSum = 0.0;
        for (int s0 = 0; s0 < n; s0++) rootSum += x[support[s0]] * sDt2[support[s0]];
        for (int s0 = 0; s0 < n; s0++) gDt2[support[s0]] = rootSum;
        for (int p = 0; p < workspace.supportPairCount; p++) {
            int pair = pairs[p];
            int i = pairFirst[pair];
            int j = pairSecond[pair];
            double k = workspace.pairK[pair];
            double kDt = workspace.pairKDt[pair];
            double kDt2 = workspace.pairKDt2[pair];
            gDt2[i] -= k * x[j] * sDt2[j] + 2.0 * kDt * x[j] * sDt[j] + kDt2 * x[j] * s[j];
            gDt2[j] -= k * x[i] * sDt2[i] + 2.0 * kDt * x[i] * sDt[i] + kDt2 * x[i] * s[i];
        }
        double lambdaOverR = HuronVidalExcess.LAMBDA / GAS_CONSTANT;
        double t2 = temperature * temperature;
        double excessEnergyDt2 = 0.0;
        for (int si = 0; si < n; si++) {
            int i = support[si];
            if (!workspace.active[i] || x[i] == 0.0) continue;
            double bi = coVolumes[i];
            double u = workspace.a[i] / bi - s[i] * g[i] / bMix;
            double uDt = workspace.daDt[i] / bi - (sDt[i] * g[i] + s[i] * gDt[i]) / bMix;
            double uDt2 = workspace.d2aDt2[i] / bi - (sDt2[i] * g[i] + 2.0 * sDt[i] * gDt[i] + s[i] * gDt2[i]) / bMix;
            double baseDt2 = lambdaOverR * (uDt2 / temperature - 2.0 * uDt / t2 + 2.0 * u / (t2 * temperature));
            double restGDt2 = 0.0;
            int[] cellsOfI = cellIndex[i];
            int[] pairsOfI = pairIndex[i];
            for (int sj = 0; sj < n; sj++) {
                int j = support[sj];
                if (x[j] == 0.0 || cellsOfI[j] >= 0) continue;
                int pair = pairsOfI[j];
                double k = pair < 0 ? 0.0 : workspace.pairK[pair];
                double kDt = pair < 0 ? 0.0 : workspace.pairKDt[pair];
                double kDt2 = pair < 0 ? 0.0 : workspace.pairKDt2[pair];
                restGDt2 += (1.0 - k) * x[j] * sDt2[j] - 2.0 * kDt * x[j] * sDt[j] - kDt2 * x[j] * s[j];
            }
            double restB = workspace.restCoVolume[i], restG = workspace.restRow[i], restGDt = workspace.restRowDt[i];
            double rest = workspace.a[i] / bi * restB - s[i] * restG;
            double restDt = workspace.daDt[i] / bi * restB - (sDt[i] * restG + s[i] * restGDt);
            double restDt2 = workspace.d2aDt2[i] / bi * restB - (sDt2[i] * restG + 2.0 * sDt[i] * restGDt + s[i] * restGDt2);
            double numeratorDt2 = lambdaOverR * (restDt2 / temperature - 2.0 * restDt / t2 + 2.0 * rest / (t2 * temperature))
                    + workspace.numeratorDt2[i];
            double thetaDt2 = (numeratorDt2 - 2.0 * workspace.thetaDt[i] * workspace.denominatorDt[i]
                    - workspace.theta[i] * workspace.denominatorDt2[i]) / workspace.denominator[i];
            excessEnergyDt2 += x[i] * (thetaDt2 - baseDt2);
        }
        double scale = GAS_CONSTANT / HuronVidalExcess.LAMBDA;
        return -scale * bMix * (2.0 * workspace.excessEnergyDt + temperature * excessEnergyDt2);
    }

    /**
     * The derivative terms of the correction that {@link #evaluateDerivatives} reads, for the composition
     * {@link #correctExcess} last formed:
     * <ul>
     *   <li>the rows' temperature derivatives {@code S_k' = s_k' g_k + s_k g_k' - (R/(2 Lambda))[b_k (dE + T dE') +
     *   b (dln gamma_k + T dln gamma_k')]}, each term of {@code dln gamma_k} differentiated as it was formed;</li>
     *   <li>the cross terms {@code crossA_kl = (1 - k_kl) s_k s_l - (R T/(2 Lambda))[b_k dln gamma_l + b_l dln gamma_k
     *   + b dN_kl]}, with {@code dN_kl = n d(dln gamma_k)/dn_l = b_l B_kl + b_k B_lk - b_k b_l dS_kl}:
     *   {@code B_kl = H_lk/D_k - eps^b_lk/b} of {@code l} around centre {@code k} ({@code H = G (tau - theta)}) and
     *   {@code dS_kl = sum_i [c_i G_ki G_li (eps_ki + eps_li) - c_i^b (eps^b_ki + eps^b_li)]},
     *   {@code c_i = x_i/D_i^2}, split into its classical part (separable, {@code O(n + pairs)} per row) and the cells'
     *   terms ({@code O(n)} per cell of a present centre).</li>
     * </ul>
     */
    private void differentiateExcess(double temperatureKelvin, Workspace workspace) {
        double[] x = workspace.composition;
        double[] s = workspace.sqrtA;
        double[] sDt = workspace.interactionRootDt;
        double[] g = workspace.g;
        double[] gDt = workspace.rowGDt;
        double bMix = workspace.excessCoVolume;
        double lambda = HuronVidalExcess.LAMBDA;
        double inverseRt = 1.0 / (GAS_CONSTANT * temperatureKelvin);
        double share = lambda * inverseRt;
        double[] logGamma = workspace.deltaLogGamma;
        double[] logGammaDt = workspace.deltaLogGammaDt;
        boolean activePresent = workspace.activePresent;
        int[] support = workspace.support;
        int n = workspace.supportCount;
        int[] pairs = workspace.supportPairs;
        int pairCount = workspace.supportPairCount;
        int[] cells = workspace.supportCells;
        int[] cellFrom = workspace.cellFrom;
        int[] cellTo = workspace.cellTo;
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            logGammaDt[k] = workspace.active[k] ? workspace.thetaDt[k] - workspace.thetaBaseDt[k] : 0.0;
        }
        if (activePresent) {
            double z1Dt = 0.0, z2Dt = 0.0, ySumDt = 0.0;
            for (int si = 0; si < n; si++) {
                int i = support[si];
                if (!workspace.active[i] || x[i] == 0.0) continue;
                double d = workspace.denominator[i];
                double omega = workspace.omega[i];
                double omegaDt = -workspace.denominatorDt[i] / (d * d);
                workspace.omegaDt[i] = omegaDt;
                z1Dt += x[i] * (workspace.daDt[i] * omega + workspace.a[i] * omegaDt) / coVolumes[i];
                ySumDt += x[i] * (sDt[i] * omega + s[i] * omegaDt);
                z2Dt += x[i] * (workspace.thetaDt[i] / d - workspace.theta[i] * workspace.denominatorDt[i] / (d * d)
                        - workspace.thetaBaseDt[i] / bMix);
            }
            double[] yDt = workspace.rowYDt;
            for (int sk = 0; sk < n; sk++) yDt[support[sk]] = ySumDt;
            for (int p = 0; p < pairCount; p++) {
                int pair = pairs[p];
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double k = workspace.pairK[pair];
                double kDt = workspace.pairKDt[pair];
                double omegaI = presentOmega(i, workspace), omegaJ = presentOmega(j, workspace);
                double omegaIDt = omegaI == 0.0 ? 0.0 : workspace.omegaDt[i];
                double omegaJDt = omegaJ == 0.0 ? 0.0 : workspace.omegaDt[j];
                yDt[i] -= k * x[j] * (sDt[j] * omegaJ + s[j] * omegaJDt) + kDt * x[j] * s[j] * omegaJ;
                yDt[j] -= k * x[i] * (sDt[i] * omegaI + s[i] * omegaIDt) + kDt * x[i] * s[i] * omegaI;
            }
            double[] y = workspace.rowY;
            double z1 = workspace.excessZ1;
            for (int sk = 0; sk < n; sk++) {
                int k = support[sk];
                logGammaDt[k] += coVolumes[k] * share * (z1Dt - z1 / temperatureKelvin)
                        - share * (sDt[k] * y[k] + s[k] * yDt[k] - s[k] * y[k] / temperatureKelvin)
                        - coVolumes[k] * z2Dt;
            }
        }
        for (int si = 0; si < n; si++) {
            int i = support[si];
            if (x[i] == 0.0) continue;
            double d = workspace.denominator[i];
            double theta = workspace.theta[i];
            double thetaDt = workspace.thetaDt[i];
            double dDt = workspace.denominatorDt[i];
            for (int at = cellFrom[i]; at < cellTo[i]; at++) {
                int c = cells[at];
                int k = cellPartner[c];
                double r = workspace.cellR[c] - workspace.cellF[c] * theta;
                double rDt = workspace.cellRDt[c] - workspace.cellFDt[c] * theta - workspace.cellF[c] * thetaDt;
                logGammaDt[k] += x[i] * coVolumes[k] * (rDt / d - r * dDt / (d * d));
            }
        }
        double halfR = GAS_CONSTANT / (2.0 * lambda);
        double energyTerm = workspace.excessEnergy + temperatureKelvin * workspace.excessEnergyDt;
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            workspace.excessRowDt[k] = sDt[k] * g[k] + s[k] * gDt[k]
                    - halfR * (coVolumes[k] * energyTerm + bMix * (logGamma[k] + temperatureKelvin * logGammaDt[k]));
        }
        // dS_kl: the classical part where a present centre has a present NRTL partner, then the cells' terms.
        double[] curvature = workspace.excessCurvature;
        double[] v = workspace.rowV;
        for (int sk = 0; sk < n; sk++) v[support[sk]] = 0.0;
        for (int si = 0; si < n; si++) {
            int i = support[si];
            if (x[i] == 0.0) continue;
            double d = workspace.denominator[i];
            double weight = x[i] / (d * d);
            double theta = workspace.theta[i];
            for (int at = cellFrom[i]; at < cellTo[i]; at++) {
                int c = cells[at];
                v[cellPartner[c]] += weight * (workspace.cellR[c] - workspace.cellF[c] * theta);
            }
        }
        double[] y2 = workspace.rowY;
        double classicalLinear = 0.0, classicalTheta = 0.0;
        if (activePresent) {
            double y2Sum = 0.0;
            double inverseB2 = 1.0 / (bMix * bMix);
            for (int si = 0; si < n; si++) {
                int i = support[si];
                if (!workspace.active[i] || x[i] == 0.0) continue;
                double d = workspace.denominator[i];
                double weightShift = x[i] / (d * d) - x[i] * inverseB2;
                workspace.omegaDt[i] = weightShift; // reused: c_i - c_i^b of a present active centre
                classicalLinear += weightShift * workspace.a[i] / coVolumes[i];
                y2Sum += weightShift * s[i];
                classicalTheta += x[i] / (d * d) * workspace.theta[i] - x[i] * inverseB2 * workspace.thetaBase[i];
            }
            for (int sk = 0; sk < n; sk++) y2[support[sk]] = y2Sum;
            for (int p = 0; p < pairCount; p++) {
                int pair = pairs[p];
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double k = workspace.pairK[pair];
                if (presentOmega(j, workspace) != 0.0) y2[i] -= k * workspace.omegaDt[j] * s[j];
                if (presentOmega(i, workspace) != 0.0) y2[j] -= k * workspace.omegaDt[i] * s[i];
            }
        }
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            int row = k * count;
            double rowClassical = activePresent ? share * (classicalLinear - s[k] / coVolumes[k] * y2[k]) - classicalTheta : 0.0;
            for (int sl = 0; sl < n; sl++) {
                int l = support[sl];
                double columnClassical = activePresent ? share * (classicalLinear - s[l] / coVolumes[l] * y2[l]) - classicalTheta : 0.0;
                curvature[row + l] = rowClassical + columnClassical + v[k] + v[l];
            }
        }
        for (int si = 0; si < n; si++) {
            int i = support[si];
            if (x[i] == 0.0 || cellFrom[i] == cellTo[i]) continue;
            double d = workspace.denominator[i];
            double weight = x[i] / (d * d);
            double theta = workspace.theta[i];
            double centreEnergy = share * workspace.a[i] / coVolumes[i] - theta;
            for (int at = cellFrom[i]; at < cellTo[i]; at++) {
                int c = cells[at];
                int k = cellPartner[c];
                double f = workspace.cellF[c];
                double r = workspace.cellR[c] - f * theta;
                if (f != 0.0) {
                    // c_i F_ki eps^cl_li into (k, l) and (l, k), eps^cl_li = tau^cl_li - theta_i.
                    double scaled = weight * f;
                    int[] pairsOfI = pairIndex[i];
                    for (int sl = 0; sl < n; sl++) {
                        int l = support[sl];
                        double classical = centreEnergy - share * (1.0 - workspace.interaction(pairsOfI[l])) * s[i] * s[l] / coVolumes[l];
                        double term = scaled * classical;
                        curvature[k * count + l] += term;
                        curvature[l * count + k] += term;
                    }
                }
                for (int at2 = cellFrom[i]; at2 < cellTo[i]; at2++) {
                    int c2 = cells[at2];
                    int l = cellPartner[c2];
                    double f2 = workspace.cellF[c2];
                    double r2 = workspace.cellR[c2] - f2 * theta;
                    curvature[k * count + l] += weight * (f2 * r + f * r2);
                }
            }
        }
        double half = GAS_CONSTANT * temperatureKelvin / (2.0 * lambda);
        for (int sk = 0; sk < n; sk++) {
            int k = support[sk];
            for (int sl = sk; sl < n; sl++) {
                int l = support[sl];
                double localKl = centreShift(k, l, share, bMix, workspace);
                double localLk = centreShift(l, k, share, bMix, workspace);
                double deltaN = coVolumes[l] * localKl + coVolumes[k] * localLk
                        - coVolumes[k] * coVolumes[l] * curvature[k * count + l];
                double quadratic = (1.0 - workspace.interaction(pairIndex[k][l])) * s[k] * s[l];
                double cross = quadratic - half * (coVolumes[k] * logGamma[l] + coVolumes[l] * logGamma[k] + bMix * deltaN);
                workspace.excessCross[k * count + l] = cross;
                workspace.excessCross[l * count + k] = cross;
            }
        }
    }

    /**
     * {@code B_kl = H_lk/D_k - eps^b_lk/b}: the shift of {@code l} around centre {@code k} from the baseline. Where the
     * centre has a present NRTL partner it is formed directly, {@code H_lk = G_lk (tau_lk - theta_k)} on a cell and
     * {@code tau^cl_lk - theta_k} otherwise, with {@code eps^b_lk = tau^cl_lk - theta_k^b} (as differences of
     * {@code 1/D_k} and {@code 1/b} terms it cancels where {@code D_k} is small, the absent cut in water of
     * {@link #correctExcess}); otherwise {@code D_k = b}, {@code theta_k = theta_k^b} and it is the cell's
     * {@code (R_lk - F_lk theta_k)/b}, or zero.
     */
    private double centreShift(int k, int l, double share, double bMix, Workspace workspace) {
        int cell = cellIndex[k][l];
        if (workspace.active[k]) {
            double theta = workspace.theta[k];
            double classical = classicalTau(k, l, share / HuronVidalExcess.LAMBDA, workspace);
            double h = cell >= 0 ? workspace.cellGTau[cell] - workspace.cellG[cell] * theta : classical - theta;
            return h / workspace.denominator[k] - (classical - workspace.thetaBase[k]) / bMix;
        }
        return cell >= 0 ? (workspace.cellR[cell] - workspace.cellF[cell] * workspace.theta[k]) / workspace.denominator[k] : 0.0;
    }


    /**
     * Precision qualifier; the EOS owns phase selection and its coalescence policy. Where floating point loses the
     * physical root the requested one stands on, the selection fails with the typed {@link NoPhysicalRoot}.
     */
    public static RootSelection selectRoot(double reducedA, double reducedB, Root root) {
        Objects.requireNonNull(root, "root");
        if (!Double.isFinite(reducedA) || reducedA < 0.0 || !Double.isFinite(reducedB) || reducedB < 0.0) {
            throw new IllegalArgumentException("PR reduced parameters must be finite and nonnegative");
        }
        double c2 = -(1.0 - reducedB);
        double c1 = reducedA - 3.0 * reducedB * reducedB - 2.0 * reducedB;
        double c0 = -(reducedA * reducedB - reducedB * reducedB - reducedB * reducedB * reducedB);
        double p = c1 - c2 * c2 / 3.0;
        double q = 2.0 * c2 * c2 * c2 / 27.0 - c2 * c1 / 3.0 + c0;
        double discriminant = q * q / 4.0 + p * p * p / 27.0;
        double offset = c2 / 3.0;
        double largest;
        double smallestPhysical;
        int physicalRootCount = 1;
        if (discriminant > COALESCENCE_DISCRIMINANT_TOLERANCE) {
            double sqrt = Math.sqrt(discriminant);
            largest = DeterministicMath.cbrt(-q / 2.0 + sqrt) + DeterministicMath.cbrt(-q / 2.0 - sqrt) - offset;
            // Compute the non-cancelling Cardano term, then use uv=-p/3 for its partner.
            // Subtracting nearly equal -q/2 and sqrt(D) before taking a cube root amplifies
            // roundoff enough to prevent the unchanged flash fugacity criterion from converging.
            // Preserve the original value when it is already backward accurate: gratuitous
            // last-bit changes to well-behaved roots can perturb finite-difference cold solves.
            if (!(largest > reducedB) || !backwardAccurate(largest, c2, c1, c0)) {
                double dominant = -q / 2.0 - Math.copySign(sqrt, q);
                double u = DeterministicMath.cbrt(dominant);
                double repaired = refineRoot(u - p / (3.0 * u) - offset, c2, c1, c0, reducedB);
                if (Double.isFinite(repaired) && repaired > reducedB
                        && (!(largest > reducedB) || Math.abs(cubicResidual(repaired, c2, c1, c0))
                        < Math.abs(cubicResidual(largest, c2, c1, c0)))) largest = repaired;
            }
            smallestPhysical = largest;
        } else if (discriminant >= -COALESCENCE_DISCRIMINANT_TOLERANCE) {
            // Preserve the existing classification and arithmetic at coalescence. A multiple
            // root is ill-conditioned; unconstrained Newton refinement could change its branch.
            double sqrt = Math.sqrt(Math.max(0.0, discriminant));
            largest = DeterministicMath.cbrt(-q / 2.0 + sqrt) + DeterministicMath.cbrt(-q / 2.0 - sqrt) - offset;
            smallestPhysical = largest;
        } else {
            double radius = 2.0 * Math.sqrt(-p / 3.0);
            double angle = DeterministicMath.acos(Math.clamp((3.0 * q / (2.0 * p)) * Math.sqrt(-3.0 / p), -1.0, 1.0)) / 3.0;
            double r0 = radius * DeterministicMath.cos(angle) - offset;
            double r1 = radius * DeterministicMath.cos(angle - 2.0 * Math.PI / 3.0) - offset;
            double r2 = radius * DeterministicMath.cos(angle - 4.0 * Math.PI / 3.0) - offset;
            double physicalBoundary = reducedB + ROOT_EPSILON;
            // Freeze membership before refinement. Each Newton interval is derived from the
            // same immutable polynomial, not from previously refined neighboring roots.
            boolean physical0 = r0 > physicalBoundary;
            boolean physical1 = r1 > physicalBoundary;
            boolean physical2 = r2 > physicalBoundary;
            if (physical0 && !backwardAccurate(r0, c2, c1, c0)) r0 = refineRoot(r0, c2, c1, c0, physicalBoundary);
            if (physical1 && !backwardAccurate(r1, c2, c1, c0)) r1 = refineRoot(r1, c2, c1, c0, physicalBoundary);
            if (physical2 && !backwardAccurate(r2, c2, c1, c0)) r2 = refineRoot(r2, c2, c1, c0, physicalBoundary);
            largest = Math.max(r0, Math.max(r1, r2));
            smallestPhysical = Double.POSITIVE_INFINITY;
            physicalRootCount = 0;
            if (physical0) {
                smallestPhysical = r0;
                physicalRootCount++;
            }
            if (physical1) {
                smallestPhysical = Math.min(smallestPhysical, r1);
                physicalRootCount++;
            }
            if (physical2) {
                smallestPhysical = Math.min(smallestPhysical, r2);
                physicalRootCount++;
            }
            if (!Double.isFinite(smallestPhysical)) {
                smallestPhysical = largest;
                physicalRootCount = 1;
            }
        }
        double selected = root == Root.VAPOR ? largest : smallestPhysical;
        if (!Double.isFinite(selected) || selected <= reducedB) throw new NoPhysicalRoot(root);
        return new RootSelection(selected, physicalRootCount, Math.max(0.0, largest - smallestPhysical));
    }

    /**
     * Improves an analytic root only inside its derivative-monotonic interval. The derivative
     * extrema separate all three real roots, so an accepted step cannot move to a neighboring root.
     * Physical membership, coalescence classification, and the original seed survive any rejection.
     */
    private static double refineRoot(double seed, double c2, double c1, double c0, double physicalBoundary) {
        if (!Double.isFinite(seed) || seed <= physicalBoundary) return seed;
        double lower = physicalBoundary;
        double upper = Double.POSITIVE_INFINITY;
        double derivativeDiscriminant = Math.fma(c2, c2, -3.0 * c1);
        if (derivativeDiscriminant > 0.0) {
            double gap = Math.sqrt(derivativeDiscriminant);
            double left = (-c2 - gap) / 3.0;
            double right = (-c2 + gap) / 3.0;
            if (seed < left) {
                upper = left;
            } else if (seed > right) {
                lower = Math.max(lower, right);
            } else if (seed > left && seed < right) {
                lower = Math.max(lower, left);
                upper = right;
            } else {
                return seed;
            }
        }
        if (!(seed > lower && seed < upper)) return seed;
        double z = seed;
        double residual = cubicResidual(z, c2, c1, c0);
        for (int iteration = 0; iteration < MAXIMUM_ROOT_REFINEMENTS; iteration++) {
            double derivative = Math.fma(Math.fma(3.0, z, 2.0 * c2), z, c1);
            double derivativeScale = (3.0 * Math.abs(z) + 2.0 * Math.abs(c2)) * Math.abs(z) + Math.abs(c1);
            if (!Double.isFinite(derivative) || !Double.isFinite(residual)
                    || Math.abs(derivative) <= 32.0 * Math.ulp(Math.max(1.0, derivativeScale))) break;
            double candidate = z - residual / derivative;
            if (!Double.isFinite(candidate) || candidate == z || candidate <= lower || candidate >= upper) break;
            double candidateResidual = cubicResidual(candidate, c2, c1, c0);
            if (!Double.isFinite(candidateResidual) || Math.abs(candidateResidual) >= Math.abs(residual)) break;
            z = candidate;
            residual = candidateResidual;
        }
        return z;
    }

    private static double cubicResidual(double z, double c2, double c1, double c0) {
        return Math.fma(Math.fma(z + c2, z, c1), z, c0);
    }

    private static boolean backwardAccurate(double z, double c2, double c1, double c0) {
        double scale = Math.abs(z * z * z) + Math.abs(c2 * z * z) + Math.abs(c1 * z) + Math.abs(c0);
        double residual = cubicResidual(z, c2, c1, c0);
        return Double.isFinite(scale) && Double.isFinite(residual)
                && Math.abs(residual) <= 8.0 * Math.ulp(scale);
    }

    /**
     * The composition, normalised into the workspace on its support (outside it the workspace holds zeros). An amount
     * outside the support is refused: the kernel forms nothing for a compound the caller did not carry.
     */
    private void normalizeInto(double[] source, Workspace workspace) {
        double[] target = workspace.composition;
        if (source == null || source.length != target.length) throw new IllegalArgumentException("Invalid composition length");
        workspace.requireKernel(this);
        boolean[] carried = workspace.inSupport;
        double total = 0.0;
        for (int i = 0; i < source.length; i++) {
            double value = source[i];
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid composition");
            if (value != 0.0 && !carried[i]) {
                if (outsideSupportNaN) throw new AssertionError("Composition has an amount of component " + i + " outside the workspace's support");
                throw new IllegalArgumentException("Composition has an amount of component " + i + " outside the workspace's support");
            }
            total += value;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Composition has no material");
        int[] support = workspace.support;
        for (int s = 0; s < workspace.supportCount; s++) target[support[s]] = source[support[s]] / total;
    }

    private static void requireState(double temperatureKelvin, double pressurePascal) {
        if (!Double.isFinite(temperatureKelvin) || !Double.isFinite(pressurePascal) || pressurePascal <= 0.0) {
            throw new IllegalArgumentException("Invalid temperature or pressure");
        }
    }

    private void requirePackageState(double temperatureKelvin, double pressurePascal) {
        requireState(temperatureKelvin, pressurePascal);
        if (temperatureKelvin < minimumTemperatureKelvin || temperatureKelvin > maximumTemperatureKelvin
                || pressurePascal < minimumPressurePascal || pressurePascal > maximumPressurePascal) {
            throw new IllegalArgumentException("State is outside the injected property-package range");
        }
    }

    private void requireLength(double[] value, String name) {
        if (value == null || value.length != count) throw new IllegalArgumentException(name + " has invalid length");
    }

    private static double kappa(double omega) {
        return omega <= 0.491
                ? 0.37464 + 1.54226 * omega - 0.26992 * omega * omega
                : 0.379642 + 1.48503 * omega - 0.164423 * omega * omega + 0.016666 * omega * omega * omega;
    }

    public enum Root { LIQUID, VAPOR }

    /**
     * No physical root (one above {@code B}) found for the requested slot. The cubic always has one mathematically, being
     * {@code -2 B^2 < 0} at {@code Z = B} and unbounded above; floating point loses it only far outside any physical
     * state ({@code A B} or {@code p^3} overflow, a hydrocarbon partial pressure of 1e183 Pa, or the root's distance above
     * {@code B} below one ulp of {@code B}, 6.6e25 Pa). An {@link IllegalStateException}, the type every caller of the
     * kernel catches; the fluid network's property layer turns it into its refused trial ({@code HydrocarbonModel.RootAbsent}).
     */
    public static final class NoPhysicalRoot extends IllegalStateException {
        public NoPhysicalRoot(Root root) {
            super("Peng-Robinson equation has no physical " + root + " root");
        }
    }

    public static final class Workspace {
        private final double[] composition;
        private final double[] a;
        private final double[] daDt;
        private final double[] sqrtA;
        private final double[] q;
        private final double[] g;
        private final double[] sumA;
        /** {@code k_ij(T)} and its two temperature derivatives per pair of the kernel's list. */
        private final double[] pairK;
        private final double[] pairKDt;
        private final double[] pairKDt2;
        /** A held line's value and two derivatives ({@link HuronVidalExcess#held}). */
        private final double[] held = new double[3];
        /** The interaction model's own values per model pair, before they are placed on the list. */
        private final double[] modelK;
        private final double[] modelKDt;
        private final double[] modelKDt2;
        /** {@code d sqrt(a_i)/dT} and its derivative at the prepared temperature, for the model and the excess terms. */
        private final double[] interactionRootDt;
        private final double[] interactionRootDt2;
        /** {@code d^2a_i/dT^2} at the prepared temperature; filled for kernels with NRTL pairs and Mathias-Copeman alpha. */
        private final double[] d2aDt2;
        /** Per NRTL cell at the prepared temperature: {@code F = G - 1}, {@code R = G tau - tau_cl} and their derivatives. */
        private final double[] cellF;
        private final double[] cellFDt;
        private final double[] cellFDt2;
        private final double[] cellR;
        private final double[] cellRDt;
        private final double[] cellRDt2;
        /** Per NRTL cell: {@code G}, {@code G tau} and the derivatives of {@code G tau}. */
        private final double[] cellG;
        private final double[] cellGTau;
        private final double[] cellGTauDt;
        private final double[] cellGTauDt2;
        /** Per active centre: the classical part over its non-partners, {@code B_i}, {@code g^r_i} and {@code g^r_i'}. */
        private final double[] restCoVolume;
        private final double[] restRow;
        private final double[] restRowDt;
        /** Per compound, the cells' correction of the last evaluation (correctExcess and its derivatives). */
        private final double[] rowGDt;
        private final double[] rowGDt2;
        private final double[] thetaBase;
        private final double[] thetaBaseDt;
        private final double[] theta;
        private final double[] thetaDt;
        private final double[] denominator;
        private final double[] denominatorDt;
        private final double[] denominatorDt2;
        private final double[] numeratorDt2;
        private final double[] omega;
        private final double[] omegaDt;
        private final double[] deltaLogGamma;
        private final double[] deltaLogGammaDt;
        private final double[] rowY;
        private final double[] rowYDt;
        private final double[] rowV;
        private final double[] excessRowDt;
        private final boolean[] active;
        /** {@code [k * n + l]}: the cross terms, and the curvature sums they are formed from. */
        private final double[] excessCross;
        private final double[] excessCurvature;
        private double excessTemperature;
        private double excessCoVolume;
        private double excessEnergy;
        private double excessEnergyDt;
        private double excessZ1;
        private double excessZ2;
        private boolean activePresent;
        /** The temperature every compound, pair and cell of the current support was prepared at, or none. */
        private long preparedTemperature = Long.MIN_VALUE;
        /**
         * The support: the compounds the caller's mixture carries, ascending ({@link #carry}). Every loop of the kernel runs
         * over it, every pair pass over the pairs with both ends in it, and every centre's cells over the partners in it
         * ({@code supportCells[cellFrom[i] .. cellTo[i] - 1]}, the kernel's cell order kept).
         */
        private final PengRobinsonKernel kernel;
        private final boolean[] inSupport;
        private final int[] support;
        private int supportCount;
        private final int[] supportPairs;
        private int supportPairCount;
        private final int[] supportCells;
        private final int[] cellFrom;
        private final int[] cellTo;
        /** Bumped at every change of the support, so an output knows its entries outside the support are current. */
        private int supportVersion;
        /** {@link #supportSnapshot()}. */
        private boolean[] snapshot;
        /** Per compound, pair and cell: the temperature its prepared values belong to. */
        private final long[] compoundStamp;
        private final long[] pairStamp;
        private final long[] cellStamp;

        private Workspace(PengRobinsonKernel kernel) {
            this.kernel = kernel;
            int count = kernel.count;
            int pairs = kernel.pairFirst.length;
            int modelPairs = kernel.interactions == null ? 0 : kernel.interactions.pairCount();
            int excessCount = kernel.excess ? count : 0;
            int cells = kernel.cellPartner.length;
            inSupport = new boolean[count];
            support = new int[count];
            supportPairs = new int[pairs];
            supportCells = new int[cells];
            cellFrom = new int[excessCount];
            cellTo = new int[excessCount];
            compoundStamp = new long[count];
            pairStamp = new long[pairs];
            cellStamp = new long[cells];
            Arrays.fill(compoundStamp, Long.MIN_VALUE);
            Arrays.fill(pairStamp, Long.MIN_VALUE);
            Arrays.fill(cellStamp, Long.MIN_VALUE);
            Arrays.fill(inSupport, true);
            composition = new double[count];
            a = new double[count];
            daDt = new double[count];
            sqrtA = new double[count];
            q = new double[count];
            g = new double[count];
            sumA = new double[count];
            pairK = new double[pairs];
            pairKDt = new double[pairs];
            pairKDt2 = new double[pairs];
            modelK = new double[modelPairs];
            modelKDt = new double[modelPairs];
            modelKDt2 = new double[modelPairs];
            interactionRootDt = new double[count];
            interactionRootDt2 = new double[count];
            d2aDt2 = new double[count];
            cellF = new double[cells];
            cellFDt = new double[cells];
            cellFDt2 = new double[cells];
            cellR = new double[cells];
            cellRDt = new double[cells];
            cellRDt2 = new double[cells];
            cellG = new double[cells];
            cellGTau = new double[cells];
            cellGTauDt = new double[cells];
            cellGTauDt2 = new double[cells];
            int e = excessCount;
            restCoVolume = new double[e];
            restRow = new double[e];
            restRowDt = new double[e];
            rowGDt = new double[e];
            rowGDt2 = new double[e];
            thetaBase = new double[e];
            thetaBaseDt = new double[e];
            theta = new double[e];
            thetaDt = new double[e];
            denominator = new double[e];
            denominatorDt = new double[e];
            denominatorDt2 = new double[e];
            numeratorDt2 = new double[e];
            omega = new double[e];
            omegaDt = new double[e];
            deltaLogGamma = new double[e];
            deltaLogGammaDt = new double[e];
            rowY = new double[e];
            rowYDt = new double[e];
            rowV = new double[e];
            excessRowDt = new double[e];
            active = new boolean[e];
            excessCross = new double[e * e];
            excessCurvature = new double[e * e];
            filter();
        }

        /**
         * Names the compounds the caller's mixture carries: those with a nonzero amount in {@code feed}. Every evaluation
         * on this workspace then prepares, sums and differentiates over them only; an amount outside them is refused, and
         * every output outside them is zero. Each output on the support keeps its bits against the whole list, since the
         * terms left out are exact zeros, summed in the same order. Set it at every entry from the feed, never from one
         * phase: a feed compound with no amount in a phase still needs its {@code ln phi} there. Unchanged support: O(n).
         *
         * @return whether the support changed
         */
        public boolean carry(double[] feed) {
            if (feed == null || feed.length != inSupport.length) throw new IllegalArgumentException("Support feed has invalid length");
            boolean changed = false;
            for (int i = 0; i < feed.length && !changed; i++) changed = (feed[i] != 0.0) != inSupport[i];
            if (!changed) return false;
            for (int i = 0; i < feed.length; i++) inSupport[i] = feed[i] != 0.0;
            filter();
            return true;
        }

        /** Carries the whole list (a new workspace's support); returns whether the support changed. */
        public boolean carryAll() {
            boolean changed = false;
            for (int i = 0; i < inSupport.length && !changed; i++) changed = !inSupport[i];
            if (!changed) return false;
            Arrays.fill(inSupport, true);
            filter();
            return true;
        }

        /** Whether the support holds {@code component}. */
        public boolean carries(int component) { return inSupport[component]; }

        /**
         * The support as a mask that is never mutated (a new one per change), for a result that records what it was
         * evaluated on; null while the whole list is carried.
         */
        public boolean[] supportSnapshot() { return snapshot; }

        /** The support of {@code other}, a workspace of the same kernel; returns whether this one's changed. */
        public boolean carrySame(Workspace other) {
            if (other.kernel != kernel) throw new IllegalArgumentException("Workspace belongs to another kernel");
            if (Arrays.equals(inSupport, other.inSupport)) return false;
            System.arraycopy(other.inSupport, 0, inSupport, 0, inSupport.length);
            filter();
            return true;
        }

        /** The support's lists, once per change: compounds, pairs with both ends in it, and each centre's cells. */
        private void filter() {
            int n = 0;
            for (int i = 0; i < inSupport.length; i++) {
                if (inSupport[i]) {
                    support[n++] = i;
                } else {
                    composition[i] = 0.0;
                }
            }
            supportCount = n;
            int p = 0;
            for (int pair = 0; pair < kernel.pairFirst.length; pair++) {
                if (inSupport[kernel.pairFirst[pair]] && inSupport[kernel.pairSecond[pair]]) supportPairs[p++] = pair;
            }
            supportPairCount = p;
            if (kernel.excess) {
                int at = 0;
                for (int s = 0; s < n; s++) {
                    int i = support[s];
                    cellFrom[i] = at;
                    for (int c = kernel.cellStart[i]; c < kernel.cellStart[i + 1]; c++) {
                        if (inSupport[kernel.cellPartner[c]]) supportCells[at++] = c;
                    }
                    cellTo[i] = at;
                }
            }
            snapshot = n == inSupport.length ? null : inSupport.clone();
            supportVersion++;
            preparedTemperature = Long.MIN_VALUE;
        }

        private void requireKernel(PengRobinsonKernel owner) {
            if (kernel != owner) throw new IllegalArgumentException("Workspace belongs to another kernel");
        }

        private double interaction(int pair) { return pair < 0 ? 0.0 : pairK[pair]; }

        /** The normalized composition of the last evaluation. The caller must not mutate it. */
        public double[] compositionView() { return composition; }
        /** {@code sqrt(a_i)} of the prepared temperature; zero exactly where the PR alpha term vanishes. */
        public double[] attractionRootsView() { return sqrtA; }
        /**
         * {@code S_i = (1/2n) d(n^2 a)/dn_i} of the last evaluation: {@code sum_j x_j a_ij} for the quadratic part,
         * less the NRTL cells' correction on a kernel with NRTL pairs. The caller must not mutate it.
         */
        public double[] attractionRowsView() { return sumA; }
        public void clear() {
            preparedTemperature = Long.MIN_VALUE;
            Arrays.fill(compoundStamp, Long.MIN_VALUE);
            Arrays.fill(pairStamp, Long.MIN_VALUE);
            Arrays.fill(cellStamp, Long.MIN_VALUE);
            Arrays.fill(composition, 0.0);
        }
    }

    /** Which workspace support an output's entries outside the support were last filled for. */
    private abstract static class SupportFill {
        private Workspace filledFor;
        private int filledVersion;

        /** True when the entries outside the workspace's support may not hold zeros (or hold the test switch's NaN). */
        final boolean outsideStale(Workspace workspace) {
            return outsideSupportNaN || filledFor != workspace || filledVersion != workspace.supportVersion;
        }

        final void markOutside(Workspace workspace, boolean nan) {
            filledFor = nan ? null : workspace;
            filledVersion = workspace.supportVersion;
        }
    }

    /** Caller-owned mutable output; public V3 results defensively copy the fugacity coefficients. */
    public static final class Evaluation extends SupportFill {
        private final double[] logFugacityCoefficients;
        private double compressibility;
        private double residualEnthalpyJoulesPerMol;
        private double aMix;
        private double bMix;
        private double daMixDt;
        private int physicalRootCount;
        private double rootSeparation;

        private Evaluation(int count) {
            logFugacityCoefficients = new double[count];
        }

        public double[] logFugacityCoefficients() { return logFugacityCoefficients.clone(); }
        /** The coefficients themselves, for a caller that copies them itself. Must not be mutated. */
        public double[] logFugacityCoefficientsView() { return logFugacityCoefficients; }
        public double logFugacityCoefficient(int component) { return logFugacityCoefficients[component]; }
        public double compressibility() { return compressibility; }
        public double residualEnthalpyJoulesPerMol() { return residualEnthalpyJoulesPerMol; }
        public double aMix() { return aMix; }
        public double bMix() { return bMix; }
        /** {@code d a_mix / dT}, recorded by the evaluation that produced {@link #aMix()}. */
        public double daMixDt() { return daMixDt; }
        public int physicalRootCount() { return physicalRootCount; }
        public double rootSeparation() { return rootSeparation; }
    }

    /**
     * Caller-owned mutable derivative output; it carries the value evaluation it was differentiated from.
     *
     * <p>The residual-enthalpy composition derivatives are absent on purpose: they are
     * {@code -R T^2 (d ln phi_i / dT)}, which is already here, and a second copy of the same numbers under a
     * different name is a second thing to keep correct.</p>
     */
    public static final class Derivatives extends SupportFill {
        private final Evaluation evaluation;
        private final double[] dLogPhiDt;
        private final double[][] dLogPhiDn;
        private final double[] partialMolarResidualEnthalpy;
        private final double[] rootDt;
        private final double[] rootDt2;
        private final double[] crossDt;
        private final double[] coVolumeRatio;
        private final double[] attractionRatio;
        private final boolean heatCapacity;
        private double dResidualEnthalpyDt;

        private Derivatives(int count, boolean heatCapacity) {
            this.heatCapacity = heatCapacity;
            evaluation = new Evaluation(count);
            dLogPhiDt = new double[count];
            dLogPhiDn = new double[count][count];
            partialMolarResidualEnthalpy = new double[count];
            rootDt = new double[count];
            rootDt2 = new double[count];
            crossDt = new double[count];
            coVolumeRatio = new double[count];
            attractionRatio = new double[count];
        }

        public Evaluation evaluation() { return evaluation; }
        public double[] dLogPhiDt() { return dLogPhiDt.clone(); }
        public double[][] dLogPhiDn() {
            double[][] copy = new double[dLogPhiDn.length][];
            for (int row = 0; row < copy.length; row++) copy[row] = dLogPhiDn[row].clone();
            return copy;
        }
        /** The row itself, for a caller assembling a Jacobian block. Must not be mutated. */
        public double[] dLogPhiDnRowView(int component) { return dLogPhiDn[component]; }
        public double[] dLogPhiDtView() { return dLogPhiDt; }
        public double[] partialMolarResidualEnthalpy() { return partialMolarResidualEnthalpy.clone(); }
        public double[] partialMolarResidualEnthalpyView() { return partialMolarResidualEnthalpy; }
        public double dResidualEnthalpyDt() {
            if (!heatCapacity) throw new IllegalStateException("This derivative output does not carry dh^R/dT");
            return dResidualEnthalpyDt;
        }
    }

    public record RootSelection(double selectedCompressibility, int physicalRootCount, double rootSeparation) {}
}

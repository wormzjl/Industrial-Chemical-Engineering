package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Arrays;
import java.util.Objects;

/**
 * Allocation-free PR78 hydrocarbon kernel. One {@link Workspace} belongs to one solve; liquid and vapor evaluations at
 * a common temperature share its temperature-dependent pure-component arithmetic. Promoted unchanged from the V3
 * column's package, the critical constants read into arrays by the constructor, so the column and the fluid network
 * evaluate their phases on the same arithmetic.
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
    private final double[][] binaryInteractions;
    private final boolean rankOneMixing;
    private final Mixing mixing;
    /**
     * The {@code i < j} entries with {@code k_ij != 0}, for {@link Mixing#SPARSE_PAIRS}; for
     * {@link Mixing#TEMPERATURE_DEPENDENT_PAIRS} the declared pairs of {@link #interactions}, whose values live in the
     * workspace ({@link #pairInteraction} is then empty).
     */
    private final int[] pairFirst;
    private final int[] pairSecond;
    private final double[] pairInteraction;
    /** The interaction model of {@link Mixing#TEMPERATURE_DEPENDENT_PAIRS}; null for the matrix plans. */
    private final PairInteractions interactions;
    /** {@code pairIndex[i][j]}: the pair of {@code (i, j)} in {@link #interactions}, or -1; null for the matrix plans. */
    private final int[][] pairIndex;
    /**
     * Per compound, true when its alpha is Mathias-Copeman with the coefficients below; null when every compound uses
     * the Soave form with PR78's kappa.
     */
    private final boolean[] mathiasCopeman;
    private final double[] alphaFirst;
    private final double[] alphaSecond;
    private final double[] alphaThird;
    /** The excess model of {@link Mixing#HURON_VIDAL}; null for the quadratic plans. */
    private final HuronVidalExcess excess;
    /**
     * The excess model's pairs, ordered, at {@code [i * n + j]} for the energy {@code dg_ji} of {@code j} around a centre
     * {@code i}: classical or NRTL, {@code alpha_ij}, a classical pair's {@code k_ij(T)} as the line
     * {@code k0 + k1 T} or the pair of the model {@link HuronVidalExcess#interactions()} that gives it (-1 for none), and
     * the line {@code dg_ji = intercept + slope T} of an NRTL pair. Empty for the quadratic plans.
     */
    private final boolean[] excessClassical;
    private final double[] excessAlpha;
    private final double[] excessInteraction;
    private final double[] excessInteractionSlope;
    private final int[] excessModelPair;
    private final double[] excessIntercept;
    private final double[] excessSlope;
    private final double minimumTemperatureKelvin;
    private final double maximumTemperatureKelvin;
    private final double minimumPressurePascal;
    private final double maximumPressurePascal;

    /**
     * How the mixture attraction is formed. The first three plans compute the same quadratic rule
     * {@code a_ij = sqrt(a_i a_j)(1 - k_ij)}; they differ only in the arithmetic that reaches it, and
     * therefore only in roundoff. {@link #HURON_VIDAL} is the excess-energy rule of a {@link HuronVidalExcess}.
     */
    public enum Mixing {
        /**
         * The column's plan: {@code O(n^2)} over the full interaction matrix, collapsing to a rank-one
         * product when every {@code k_ij} is zero. Every V3 state has been evaluated this way and stays
         * bitwise on it.
         */
        CLASSICAL,
        /**
         * {@code O(n + pairs)}: the row sums are the rank-one sum corrected by the few nonzero pairs,
         * {@code S_i = sqrt(a_i) (sum_j q_j - sum_{j: k_ij != 0} k_ij q_j)} with {@code q_i = x_i sqrt(a_i)}.
         * For a package whose interaction matrix is nearly empty - the fluid network's has 11 nonzero pairs
         * among 21 components - this is the same rule at a fraction of the work. The mixture sums are then
         * accumulated with the identical expressions {@link #CLASSICAL}'s dense branch uses, so the two
         * differ only in how each {@code S_i} was summed.
         */
        SPARSE_PAIRS,
        /**
         * {@link #SPARSE_PAIRS}'s structure with pair values that depend on temperature: the pair list is the
         * {@link PairInteractions} model's, fixed at construction, and {@link #prepareTemperature} fills
         * {@code k_ij(T)}, {@code dk_ij/dT} and {@code d^2k_ij/dT^2} per pair into the workspace. The value sums are
         * {@link #SPARSE_PAIRS}'s expressions on those values; every temperature derivative gains the interactions' own
         * terms ({@code -2 sum_pairs q_i q_j dk_ij/dT} in {@code da_mix/dT}, and their counterparts in
         * {@code d^2a_mix/dT^2} and {@code dS_i/dT}). On a model whose pairs are all constant those terms are exact
         * zeros and the plan reproduces {@link #SPARSE_PAIRS} on the same matrix bit for bit. Only the constructor that
         * takes a {@link PairInteractions} selects it.
         */
        TEMPERATURE_DEPENDENT_PAIRS,
        /**
         * The Huron-Vidal rule of a {@link HuronVidalExcess} model: {@code a/b = sum_i x_i a_i/b_i - gE/Lambda} with
         * the co-volume-weighted NRTL {@code gE}, written as {@code n^2 a = (n b)(n Q)}, {@code Q = a/b}. Its
         * temperature-only part ({@code tau_ji}, {@code G_ji} and their two temperature derivatives, the classical
         * pairs' energies converted from their {@code k_ij(T)}: constant, linear in T, or a {@link PairInteractions}
         * model's, evaluated per temperature as {@link #TEMPERATURE_DEPENDENT_PAIRS} does) is filled by
         * {@link #prepareTemperature}; the composition
         * sums are dense {@code O(n^2)} (and {@code O(n^3)} for {@code d ln phi/dn}). With every pair classical it is the
         * quadratic rule, to roundoff. Only {@link #huronVidal} selects it.
         */
        HURON_VIDAL
    }

    /**
     * @param criticalTemperaturesKelvin per component, in the caller's basis order
     * @param binaryInteractions square, symmetric, zero diagonal; copied
     */
    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors, binaryInteractions,
                minimumTemperatureKelvin, maximumTemperatureKelvin,
                minimumPressurePascal, maximumPressurePascal, Mixing.CLASSICAL);
    }

    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal, Mixing mixing) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors, binaryInteractions, null, null, null,
                minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal, mixing);
    }

    /**
     * The kernel on a pair-interaction model, with {@link Mixing#TEMPERATURE_DEPENDENT_PAIRS}.
     *
     * @param interactions over the same basis, in the caller's order
     */
    public PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            PairInteractions interactions,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal) {
        this(criticalTemperaturesKelvin, criticalPressuresPascal, acentricFactors,
                zeroMatrix(criticalTemperaturesKelvin), Objects.requireNonNull(interactions, "interactions"), null, null,
                minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal,
                Mixing.TEMPERATURE_DEPENDENT_PAIRS);
    }

    /**
     * The kernel on a Huron-Vidal excess model, with {@link Mixing#HURON_VIDAL}, and an optional Mathias-Copeman alpha
     * per compound: {@code alpha = (1 + c1 u + c2 u^2 + c3 u^3)^2}, {@code u = 1 - sqrt(T/Tc)}, and above {@code Tc}
     * only {@code c1} ({@code alpha = (1 + c1 u)^2}, so value and slope are continuous at {@code Tc}). A compound
     * without coefficients keeps the Soave form with PR78's kappa of its acentric factor.
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
                zeroMatrix(criticalTemperaturesKelvin), null, Objects.requireNonNull(excess, "excess"), mathiasCopeman,
                minimumTemperatureKelvin, maximumTemperatureKelvin, minimumPressurePascal, maximumPressurePascal,
                Mixing.HURON_VIDAL);
    }

    private PengRobinsonKernel(
            double[] criticalTemperaturesKelvin, double[] criticalPressuresPascal, double[] acentricFactors,
            double[][] binaryInteractions, PairInteractions interactions,
            HuronVidalExcess excess, double[][] mathiasCopemanCoefficients,
            double minimumTemperatureKelvin, double maximumTemperatureKelvin,
            double minimumPressurePascal, double maximumPressurePascal, Mixing mixing) {
        this.mixing = Objects.requireNonNull(mixing, "mixing");
        if ((mixing == Mixing.TEMPERATURE_DEPENDENT_PAIRS) != (interactions != null)) {
            throw new IllegalArgumentException("TEMPERATURE_DEPENDENT_PAIRS is selected by, and only by, a PairInteractions model");
        }
        if ((mixing == Mixing.HURON_VIDAL) != (excess != null)) {
            throw new IllegalArgumentException("HURON_VIDAL is selected by, and only by, a HuronVidalExcess model");
        }
        Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin");
        Objects.requireNonNull(criticalPressuresPascal, "criticalPressuresPascal");
        Objects.requireNonNull(acentricFactors, "acentricFactors");
        Objects.requireNonNull(binaryInteractions, "binaryInteractions");
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
        this.binaryInteractions = new double[binaryInteractions.length][];
        for (int i = 0; i < binaryInteractions.length; i++) this.binaryInteractions[i] = binaryInteractions[i].clone();
        if (this.binaryInteractions.length != count) throw new IllegalArgumentException("Invalid BIP matrix dimension");
        boolean allZero = true;
        for (int i = 0; i < count; i++) {
            if (this.binaryInteractions[i].length != count) throw new IllegalArgumentException("Invalid BIP matrix row");
            criticalA[i] = 0.45724 * GAS_CONSTANT * GAS_CONSTANT
                    * criticalTemperatures[i] * criticalTemperatures[i]
                    / criticalPressures[i];
            coVolumes[i] = 0.07780 * GAS_CONSTANT * criticalTemperatures[i]
                    / criticalPressures[i];
            kappas[i] = kappa(this.acentricFactors[i]);
            for (int j = 0; j < count; j++) {
                double value = this.binaryInteractions[i][j];
                if (!Double.isFinite(value) || Math.abs(value - this.binaryInteractions[j][i]) > 1.0e-14
                        || (i == j && Math.abs(value) > 1.0e-14)) {
                    throw new IllegalArgumentException("BIP matrix must be finite, symmetric, and zero diagonal");
                }
                allZero &= value == 0.0;
            }
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
        this.excess = excess;
        int cells = excess == null ? 0 : count * count;
        this.excessClassical = new boolean[cells];
        this.excessAlpha = new double[cells];
        this.excessInteraction = new double[cells];
        this.excessInteractionSlope = new double[cells];
        this.excessModelPair = new int[cells];
        this.excessIntercept = new double[cells];
        this.excessSlope = new double[cells];
        // The excess plan's classical pairs from a model evaluate it in the workspace like TEMPERATURE_DEPENDENT_PAIRS.
        this.interactions = excess != null ? excess.interactions() : interactions;
        if (excess != null) {
            // The excess path: the matrix above is the zero placeholder, so only the critical constants were read from
            // the loop; the pairs are the model's own, copied ordered so the composition sums read one row per centre.
            if (excess.componentCount() != count) throw new IllegalArgumentException("Excess model basis does not match the components");
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < count; j++) {
                    int at = i * count + j;
                    excessClassical[at] = excess.classical(i, j);
                    excessAlpha[at] = excess.alpha(i, j);
                    excessInteraction[at] = excess.interaction(i, j);
                    excessInteractionSlope[at] = excess.interactionSlope(i, j);
                    excessModelPair[at] = excess.interactionPair(i, j);
                    excessIntercept[at] = excess.energyIntercept(j, i);
                    excessSlope[at] = excess.energySlope(j, i);
                }
            }
            this.rankOneMixing = excess.rankOne();
            this.pairFirst = new int[0];
            this.pairSecond = new int[0];
            this.pairInteraction = new double[0];
            this.pairIndex = null;
            return;
        }
        if (interactions != null) {
            // The model path: the matrix above is the zero placeholder the delegating constructor passed, so only the
            // critical constants were read from this loop; the pairs are the model's own, in its row-major order.
            if (interactions.componentCount() != count) throw new IllegalArgumentException("Interaction model basis does not match the components");
            int pairs = interactions.pairCount();
            this.rankOneMixing = pairs == 0;
            this.pairFirst = new int[pairs];
            this.pairSecond = new int[pairs];
            this.pairInteraction = new double[0];
            this.pairIndex = new int[count][count];
            for (int[] row : pairIndex) Arrays.fill(row, -1);
            for (int pair = 0; pair < pairs; pair++) {
                pairFirst[pair] = interactions.first(pair);
                pairSecond[pair] = interactions.second(pair);
                pairIndex[pairFirst[pair]][pairSecond[pair]] = pair;
                pairIndex[pairSecond[pair]][pairFirst[pair]] = pair;
            }
            return;
        }
        this.pairIndex = null;
        this.rankOneMixing = allZero;
        int pairs = 0;
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) if (this.binaryInteractions[i][j] != 0.0) pairs++;
        this.pairFirst = new int[pairs];
        this.pairSecond = new int[pairs];
        this.pairInteraction = new double[pairs];
        int at = 0;
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
            if (this.binaryInteractions[i][j] == 0.0) continue;
            pairFirst[at] = i;
            pairSecond[at] = j;
            pairInteraction[at++] = this.binaryInteractions[i][j];
        }
    }

    private static double[][] zeroMatrix(double[] criticalTemperaturesKelvin) {
        int count = Objects.requireNonNull(criticalTemperaturesKelvin, "criticalTemperaturesKelvin").length;
        return new double[count][count];
    }

    public int componentCount() { return count; }
    /** True when the package has no nonzero interaction at all, whatever plan is selected. */
    public boolean usesRankOneMixing() { return rankOneMixing; }
    /** {@code 0.07780 R Tc / Pc} for one component; the co-volume the mixture rule sums. */
    public double coVolume(int component) { return coVolumes[component]; }
    public Workspace newWorkspace() {
        if (excess != null) return new Workspace(count, interactions == null ? 0 : interactions.pairCount(), count);
        return interactions == null ? new Workspace(count) : new Workspace(count, interactions.pairCount(), 0);
    }

    /** The plan this kernel evaluates its mixture attraction with. */
    public Mixing mixing() { return mixing; }

    /** The pairs' {@code k_ij} of the sparse plans: the constants, or the workspace's prepared {@code k_ij(T)}. */
    private double[] pairInteractions(Workspace workspace) {
        return mixing == Mixing.SPARSE_PAIRS ? pairInteraction : workspace.pairK;
    }
    public Evaluation newEvaluation() { return new Evaluation(count); }
    public Derivatives newDerivatives() { return new Derivatives(count); }

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
        if (workspace.preparedTemperature == Double.doubleToLongBits(temperatureKelvin)) return;
        for (int i = 0; i < count; i++) {
            if (mathiasCopeman != null && mathiasCopeman[i]) {
                prepareMathiasCopeman(i, temperatureKelvin, workspace);
                continue;
            }
            double sqrtTr = Math.sqrt(temperatureKelvin / criticalTemperatures[i]);
            double alphaTerm = 1.0 + kappas[i] * (1.0 - sqrtTr);
            workspace.a[i] = criticalA[i] * alphaTerm * alphaTerm;
            workspace.daDt[i] = -criticalA[i] * kappas[i] * alphaTerm
                    / Math.sqrt(temperatureKelvin * criticalTemperatures[i]);
            workspace.sqrtA[i] = Math.sqrt(workspace.a[i]);
            if (excess != null) workspace.d2aDt2[i] = soaveSecondDerivative(i, temperatureKelvin);
        }
        if (excess != null) prepareExcess(temperatureKelvin, workspace);
        else if (interactions != null) prepareInteractions(temperatureKelvin, workspace);
        workspace.preparedTemperature = Double.doubleToLongBits(temperatureKelvin);
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
     * The temperature-only part of {@link Mixing#HURON_VIDAL}, per ordered pair {@code (j around i)}: the energy
     * {@code dg_ji} and its two temperature derivatives, then {@code tau = dg/(R T)},
     * {@code tau' = dg'/(R T) - tau/T}, {@code tau'' = dg''/(R T) - 2 tau'/T}, {@code G = exp(-alpha tau)},
     * {@code G' = -alpha tau' G}, {@code G'' = alpha (alpha tau'^2 - tau'') G}. An NRTL pair's energy is its line
     * ({@code dg'' = 0}); a classical pair's is {@code dg_ji = Lambda a_i/b_i - c (1 - k_ij) P}, with
     * {@code c = 2 Lambda/(b_i + b_j)}, {@code P = s_i s_j}, {@code s = sqrt(a)}:
     * {@code dg' = Lambda a_i'/b_i - c [(1 - k) P' - k' P]}, {@code dg'' = Lambda a_i''/b_i - c [(1 - k) P'' - 2 k' P' - k'' P]},
     * on {@code ds/dT} and {@code d^2s/dT^2}. Its {@code k_ij(T)} is the line {@code k0 + k1 T} ({@code k'' = 0}; a
     * constant pair has {@code k1 = 0}, so its added terms are exact zeros) or the interaction model's value with its two
     * derivatives, filled per model pair into the workspace first. The diagonal is exactly {@code tau = 0}, {@code G = 1}.
     * A value that is not finite (a classical pair where {@code sqrt(a_i)} vanishes) is refused before the temperature is
     * recorded as prepared.
     */
    private void prepareExcess(double temperatureKelvin, Workspace workspace) {
        workspace.preparedTemperature = Long.MIN_VALUE;
        double[] rootDt = workspace.interactionRootDt;
        double[] rootDt2 = workspace.interactionRootDt2;
        prepareRootDerivatives(temperatureKelvin, workspace, rootDt, rootDt2);
        if (interactions != null) {
            interactions.evaluate(temperatureKelvin, workspace.sqrtA, rootDt, rootDt2, coVolumes,
                    workspace.pairK, workspace.pairKDt, workspace.pairKDt2);
        }
        double lambda = HuronVidalExcess.LAMBDA;
        double rt = GAS_CONSTANT * temperatureKelvin;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                int at = i * count + j;
                if (i == j) {
                    workspace.tau[at] = 0.0;
                    workspace.tauDt[at] = 0.0;
                    workspace.tauDt2[at] = 0.0;
                    workspace.weightG[at] = 1.0;
                    workspace.weightGDt[at] = 0.0;
                    workspace.weightGDt2[at] = 0.0;
                    continue;
                }
                double energy;
                double energyDt;
                double energyDt2;
                if (excessClassical[at]) {
                    int pair = excessModelPair[at];
                    double k;
                    double kDt;
                    double kDt2;
                    if (pair >= 0) {
                        k = workspace.pairK[pair];
                        kDt = workspace.pairKDt[pair];
                        kDt2 = workspace.pairKDt2[pair];
                    } else {
                        k = excessInteraction[at] + excessInteractionSlope[at] * temperatureKelvin;
                        kDt = excessInteractionSlope[at];
                        kDt2 = 0.0;
                    }
                    double weight = 2.0 * lambda / (coVolumes[i] + coVolumes[j]);
                    double share = 2.0 * lambda * (1.0 - k) / (coVolumes[i] + coVolumes[j]);
                    double si = workspace.sqrtA[i];
                    double sj = workspace.sqrtA[j];
                    double product = si * sj;
                    double productDt = rootDt[i] * sj + si * rootDt[j];
                    double productDt2 = rootDt2[i] * sj + 2.0 * rootDt[i] * rootDt[j] + si * rootDt2[j];
                    energy = lambda * workspace.a[i] / coVolumes[i] - share * si * sj;
                    energyDt = lambda * workspace.daDt[i] / coVolumes[i] - share * productDt
                            + weight * kDt * product;
                    energyDt2 = lambda * workspace.d2aDt2[i] / coVolumes[i] - share * productDt2
                            + weight * (kDt2 * product + 2.0 * kDt * productDt);
                } else {
                    energy = excessIntercept[at] + excessSlope[at] * temperatureKelvin;
                    energyDt = excessSlope[at];
                    energyDt2 = 0.0;
                }
                double tau = energy / rt;
                double tauDt = energyDt / rt - tau / temperatureKelvin;
                double tauDt2 = energyDt2 / rt - 2.0 * tauDt / temperatureKelvin;
                double alpha = excessAlpha[at];
                double g = DeterministicMath.exp(-alpha * tau);
                double gDt = -alpha * tauDt * g;
                double gDt2 = alpha * (alpha * tauDt * tauDt - tauDt2) * g;
                if (!Double.isFinite(tau) || !Double.isFinite(tauDt) || !Double.isFinite(tauDt2)
                        || !Double.isFinite(g) || !Double.isFinite(gDt) || !Double.isFinite(gDt2)) {
                    throw new IllegalArgumentException("Excess energy of component " + j + " around " + i
                            + " is not finite at " + temperatureKelvin + " K");
                }
                workspace.tau[at] = tau;
                workspace.tauDt[at] = tauDt;
                workspace.tauDt2[at] = tauDt2;
                workspace.weightG[at] = g;
                workspace.weightGDt[at] = gDt;
                workspace.weightGDt2[at] = gDt2;
            }
        }
    }

    /**
     * {@code k_ij(T)} and its two temperature derivatives per pair of {@link #interactions}, on the pure-component
     * values the loop above just filled: O(pairs) per temperature, one {@code pow} per group term. A value that is not
     * finite (the Soave {@code sqrt(a_i)} vanishes at {@code Tr = (1 + 1/kappa)^2}) is refused before the temperature is
     * recorded as prepared, so the workspace never caches it.
     */
    private void prepareInteractions(double temperatureKelvin, Workspace workspace) {
        workspace.preparedTemperature = Long.MIN_VALUE;
        if (interactions.temperatureDependent()) {
            prepareRootDerivatives(temperatureKelvin, workspace, workspace.interactionRootDt, workspace.interactionRootDt2);
        }
        interactions.evaluate(temperatureKelvin, workspace.sqrtA, workspace.interactionRootDt,
                workspace.interactionRootDt2, coVolumes, workspace.pairK, workspace.pairKDt, workspace.pairKDt2);
        for (int pair = 0; pair < pairFirst.length; pair++) {
            if (!Double.isFinite(workspace.pairK[pair]) || !Double.isFinite(workspace.pairKDt[pair])
                    || !Double.isFinite(workspace.pairKDt2[pair])) {
                throw new IllegalArgumentException("Pair interaction of components " + pairFirst[pair] + " and "
                        + pairSecond[pair] + " is not finite at " + temperatureKelvin + " K");
            }
        }
    }

    private void evaluatePrepared(
            double temperatureKelvin, double pressurePascal, double[] composition, Root root,
            Workspace workspace, Evaluation output) {
        requirePackageState(temperatureKelvin, pressurePascal);
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(output, "output");
        normalizeInto(composition, workspace.composition);
        double bMix = 0.0;
        for (int i = 0; i < count; i++) {
            bMix += workspace.composition[i] * coVolumes[i];
            workspace.q[i] = workspace.sqrtA[i] * workspace.composition[i];
        }
        double aMix;
        double daMixDt;
        if (mixing == Mixing.HURON_VIDAL) {
            // a = b Q, da/dT = b dQ/dT; sumA_i = (1/2n) d(n^2 a)/dn_i = (b_i Q + b Qbar_i)/2.
            mixExcess(temperatureKelvin, workspace, bMix);
            aMix = bMix * workspace.excessQ;
            daMixDt = bMix * workspace.excessQDt;
        } else if (mixing == Mixing.SPARSE_PAIRS || mixing == Mixing.TEMPERATURE_DEPENDENT_PAIRS) {
            // g_i = sum_j (1 - k_ij) q_j, reached as the rank-one sum minus the few pairs that are not zero; the
            // temperature-dependent plan reads the prepared k_ij(T).
            double[] k = pairInteractions(workspace);
            double qSum = 0.0;
            for (int i = 0; i < count; i++) qSum += workspace.q[i];
            for (int i = 0; i < count; i++) workspace.g[i] = qSum;
            for (int pair = 0; pair < pairFirst.length; pair++) {
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double interaction = k[pair];
                workspace.g[i] -= interaction * workspace.q[j];
                workspace.g[j] -= interaction * workspace.q[i];
            }
            // From here the dense branch's own expressions, so the plans differ only in how g_i was summed.
            aMix = 0.0;
            daMixDt = 0.0;
            for (int i = 0; i < count; i++) {
                workspace.sumA[i] = workspace.sqrtA[i] * workspace.g[i];
                aMix += workspace.q[i] * workspace.g[i];
                double dq = workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
                daMixDt += 2.0 * dq * workspace.g[i];
            }
            if (mixing == Mixing.TEMPERATURE_DEPENDENT_PAIRS) {
                // d/dT of -sum_ij q_i q_j k_ij at fixed q: -2 sum_pairs q_i q_j dk_ij/dT.
                double interactionDt = 0.0;
                for (int pair = 0; pair < pairFirst.length; pair++) {
                    interactionDt += workspace.pairKDt[pair] * workspace.q[pairFirst[pair]] * workspace.q[pairSecond[pair]];
                }
                daMixDt -= 2.0 * interactionDt;
            }
        } else if (rankOneMixing) {
            double qSum = 0.0;
            double dqSum = 0.0;
            for (int i = 0; i < count; i++) {
                qSum += workspace.q[i];
                dqSum += workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
            }
            aMix = qSum * qSum;
            daMixDt = 2.0 * qSum * dqSum;
            for (int i = 0; i < count; i++) workspace.sumA[i] = workspace.sqrtA[i] * qSum;
        } else {
            for (int i = 0; i < count; i++) {
                double g = 0.0;
                for (int j = 0; j < count; j++) g += (1.0 - binaryInteractions[i][j]) * workspace.q[j];
                workspace.g[i] = g;
                workspace.sumA[i] = workspace.sqrtA[i] * g;
            }
            aMix = 0.0;
            daMixDt = 0.0;
            for (int i = 0; i < count; i++) {
                aMix += workspace.q[i] * workspace.g[i];
                double dq = workspace.composition[i] * workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
                daMixDt += 2.0 * dq * workspace.g[i];
            }
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
        for (int i = 0; i < count; i++) {
            double bRatio = coVolumes[i] / bMix;
            double attractionRatio = 2.0 * workspace.sumA[i] / aMix - bRatio;
            output.logFugacityCoefficients[i] = bRatio * (z - 1.0) - freeVolumeLog
                    - attraction * attractionRatio * logRatio;
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
        double[] x = workspace.composition;
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
        double secondDaDt = secondTemperatureDerivative(
                temperatureKelvin, workspace, output.rootDt, output.rootDt2, output.crossDt);
        boolean huronVidal = mixing == Mixing.HURON_VIDAL;
        if (huronVidal) differentiateExcess(temperatureKelvin, workspace);
        double reducedADt = reducedA * (daDt / a - 2.0 / temperatureKelvin);
        double reducedBDt = -reducedB / temperatureKelvin;
        double zDt = zByReducedA * reducedADt + zByReducedB * reducedBDt;
        double logRatioDt = (zDt + (1.0 + SQRT_TWO) * reducedBDt) / lower - (zDt + (1.0 - SQRT_TWO) * reducedBDt) / upper;
        double attractionDt = attraction * (reducedADt / reducedA - reducedBDt / reducedB);
        for (int i = 0; i < count; i++) {
            output.coVolumeRatio[i] = coVolumes[i] / b;
            output.attractionRatio[i] = 2.0 * workspace.sumA[i] / a - output.coVolumeRatio[i];
            double sumADt = huronVidal ? workspace.excessRowDt[i]
                    : output.rootDt[i] * (workspace.sumA[i] / workspace.sqrtA[i])
                    + workspace.sqrtA[i] * output.crossDt[i];
            double attractionRatioDt = 2.0 * (sumADt * a - workspace.sumA[i] * daDt) / (a * a);
            output.dLogPhiDt[i] = output.coVolumeRatio[i] * zDt - (zDt - reducedBDt) / (z - reducedB)
                    - (attractionDt * output.attractionRatio[i] * logRatio
                    + attraction * attractionRatioDt * logRatio
                    + attraction * output.attractionRatio[i] * logRatioDt);
        }
        for (int j = 0; j < count; j++) {
            double reducedAdn = 2.0 * reducedA * (workspace.sumA[j] - a) / a;
            double coVolumeShift = (coVolumes[j] - b) / b;
            double reducedBdn = reducedB * coVolumeShift;
            double zDn = zByReducedA * reducedAdn + zByReducedB * reducedBdn;
            double logRatioDn = (zDn + (1.0 + SQRT_TWO) * reducedBdn) / lower - (zDn + (1.0 - SQRT_TWO) * reducedBdn) / upper;
            double attractionDn = attraction * (reducedAdn / reducedA - reducedBdn / reducedB);
            double sharedTerms = -(zDn - reducedBdn) / (z - reducedB);
            double attractionShift = 2.0 * (workspace.sumA[j] - a);
            double rootAj = workspace.sqrtA[j];
            for (int i = 0; i < count; i++) {
                double bRatioDn = -output.coVolumeRatio[i] * coVolumeShift;
                // A composition derivative at fixed T: k_ij(T) enters by value only. crossA_ij is
                // (1/2) d^2(n^2 a)/dn_i dn_j, which is a_ij for the quadratic plans.
                double crossA = huronVidal ? workspace.excessCross[i * count + j]
                        : (pairIndex != null ? 1.0 - workspace.interaction(pairIndex[i][j])
                        : rankOneMixing ? 1.0 : 1.0 - binaryInteractions[i][j])
                        * workspace.sqrtA[i] * rootAj;
                double attractionRatioDn = 2.0 * ((crossA - workspace.sumA[i]) * a
                        - workspace.sumA[i] * attractionShift) / (a * a) - bRatioDn;
                output.dLogPhiDn[i][j] = bRatioDn * (z - 1.0) + output.coVolumeRatio[i] * zDn + sharedTerms
                        - (attractionDn * output.attractionRatio[i] * logRatio
                        + attraction * attractionRatioDn * logRatio
                        + attraction * output.attractionRatio[i] * logRatioDn);
            }
        }
        output.dResidualEnthalpyDt = GAS_CONSTANT * (z - 1.0) + GAS_CONSTANT * temperatureKelvin * zDt
                + temperatureKelvin * secondDaDt / (2.0 * SQRT_TWO * b) * logRatio
                + (temperatureKelvin * daDt - a) / (2.0 * SQRT_TWO * b) * logRatioDt;
        for (int i = 0; i < count; i++) {
            output.partialMolarResidualEnthalpy[i] =
                    -GAS_CONSTANT * temperatureKelvin * temperatureKelvin * output.dLogPhiDt[i];
        }
    }

    /**
     * {@code d^2 a_mix / dT^2} of the mixture the preceding evaluation left in {@code workspace}, filling the
     * three caller-owned scratch vectors it needs on the way: {@code d sqrt(a_i)/dT}, its own derivative, and
     * the interaction-weighted composition sum {@code sum_j (1-k_ij) x_j d sqrt(a_j)/dT} (with
     * {@link Mixing#TEMPERATURE_DEPENDENT_PAIRS} also {@code - sum_j q_j dk_ij/dT}, the whole {@code d(S_i/sqrt(a_i))/dT}).
     *
     * <p>One expression, two callers: {@link #evaluateDerivatives} needs the two root vectors and the cross
     * sum again for {@code d ln phi_i/dT}, and the network's volumetric block needs only the scalar. Splitting
     * it would give the same number two arithmetic paths.</p>
     *
     * <p>With {@link Mixing#HURON_VIDAL} the two root vectors are filled as for the other plans, and {@code crossDt} is
     * left untouched: {@link #evaluateDerivatives} reads that plan's row derivatives from the workspace instead.</p>
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
        for (int i = 0; i < count; i++) {
            double secondADt = mathiasCopeman != null && mathiasCopeman[i]
                    ? workspace.d2aDt2[i] : soaveSecondDerivative(i, temperatureKelvin);
            rootDt[i] = workspace.daDt[i] / (2.0 * workspace.sqrtA[i]);
            rootDt2[i] = secondADt / (2.0 * workspace.sqrtA[i])
                    - workspace.daDt[i] * workspace.daDt[i]
                    / (4.0 * workspace.sqrtA[i] * workspace.sqrtA[i] * workspace.sqrtA[i]);
        }
    }

    /**
     * The composition-dependent half, on the root derivatives {@link #prepareRootDerivatives} filled. With
     * {@link Mixing#HURON_VIDAL} the value is formed from the workspace's own prepared terms (the excess model is not a
     * sum over {@code sqrt(a_i)}), and {@code rootDt}, {@code rootDt2} and {@code crossDt} are neither read nor written.
     */
    public double mixtureSecondTemperatureDerivative(
            Workspace workspace, double[] rootDt, double[] rootDt2, double[] crossDt) {
        if (mixing == Mixing.HURON_VIDAL) return excessSecondTemperatureDerivative(workspace);
        double[] x = workspace.composition;
        if (mixing == Mixing.SPARSE_PAIRS || mixing == Mixing.TEMPERATURE_DEPENDENT_PAIRS) {
            double[] k = pairInteractions(workspace);
            double interactionSum = 0.0;
            for (int i = 0; i < count; i++) interactionSum += x[i] * rootDt[i];
            for (int i = 0; i < count; i++) crossDt[i] = interactionSum;
            for (int pair = 0; pair < pairFirst.length; pair++) {
                int i = pairFirst[pair];
                int j = pairSecond[pair];
                double interaction = k[pair];
                crossDt[i] -= interaction * x[j] * rootDt[j];
                crossDt[j] -= interaction * x[i] * rootDt[i];
            }
        } else if (rankOneMixing) {
            double interactionSum = 0.0;
            for (int i = 0; i < count; i++) interactionSum += x[i] * rootDt[i];
            for (int i = 0; i < count; i++) crossDt[i] = interactionSum;
        } else {
            for (int i = 0; i < count; i++) {
                double sum = 0.0;
                for (int j = 0; j < count; j++) sum += (1.0 - binaryInteractions[i][j]) * x[j] * rootDt[j];
                crossDt[i] = sum;
            }
        }
        double secondDaDt = 0.0;
        for (int i = 0; i < count; i++) {
            secondDaDt += 2.0 * x[i] * (rootDt2[i] * (workspace.sumA[i] / workspace.sqrtA[i])
                    + rootDt[i] * crossDt[i]);
        }
        if (mixing == Mixing.TEMPERATURE_DEPENDENT_PAIRS) {
            secondDaDt = addInteractionTemperatureTerms(workspace, rootDt, crossDt, secondDaDt);
        }
        return secondDaDt;
    }

    /**
     * The terms {@link Mixing#TEMPERATURE_DEPENDENT_PAIRS} adds once the constant-interaction sums above are formed,
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
        for (int pair = 0; pair < pairFirst.length; pair++) {
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
     * The composition sums of {@link Mixing#HURON_VIDAL} on the prepared {@code tau}, {@code G}, with
     * {@code w_j = x_j b_j}, per centre {@code i}: {@code D_i = sum_j w_j G_ji}, {@code C_i = sum_j w_j G_ji tau_ji},
     * {@code theta_i = C_i/D_i}, {@code theta_i' = (C_i' - theta_i D_i')/D_i}; {@code E = gE/(R T) = sum_i x_i theta_i};
     * {@code ln gamma_k = theta_k + sum_i x_i M_ki (tau_ki - theta_i)}, {@code M_ki = b_k G_ki/D_i}. Then, with
     * {@code r = R/Lambda}, {@code Q = sum_i x_i a_i/b_i - r T E}, {@code Q' = sum_i x_i a_i'/b_i - r (E + T E')},
     * {@code Qbar_i = d(n Q)/dn_i = a_i/b_i - r T ln gamma_i}, and the rows {@code sumA_i = (b_i Q + b Qbar_i)/2}.
     */
    private void mixExcess(double temperatureKelvin, Workspace workspace, double bMix) {
        double[] x = workspace.composition;
        double[] w = workspace.excessWeight;
        for (int j = 0; j < count; j++) w[j] = x[j] * coVolumes[j];
        double excessEnergy = 0.0;
        double excessEnergyDt = 0.0;
        double linear = 0.0;
        double linearDt = 0.0;
        for (int i = 0; i < count; i++) {
            int row = i * count;
            double denominator = 0.0;
            double numerator = 0.0;
            double denominatorDt = 0.0;
            double numeratorDt = 0.0;
            for (int j = 0; j < count; j++) {
                double weight = w[j];
                double g = workspace.weightG[row + j];
                double gDt = workspace.weightGDt[row + j];
                double tau = workspace.tau[row + j];
                denominator += weight * g;
                numerator += weight * g * tau;
                denominatorDt += weight * gDt;
                numeratorDt += weight * (gDt * tau + g * workspace.tauDt[row + j]);
            }
            double theta = numerator / denominator;
            workspace.excessDenominator[i] = denominator;
            workspace.excessDenominatorDt[i] = denominatorDt;
            workspace.excessTheta[i] = theta;
            workspace.excessThetaDt[i] = (numeratorDt - theta * denominatorDt) / denominator;
            excessEnergy += x[i] * theta;
            excessEnergyDt += x[i] * workspace.excessThetaDt[i];
            linear += x[i] * workspace.a[i] / coVolumes[i];
            linearDt += x[i] * workspace.daDt[i] / coVolumes[i];
        }
        for (int k = 0; k < count; k++) {
            double logGamma = workspace.excessTheta[k];
            for (int i = 0; i < count; i++) {
                int at = i * count + k;
                logGamma += x[i] * coVolumes[k] * workspace.weightG[at] * (workspace.tau[at] - workspace.excessTheta[i])
                        / workspace.excessDenominator[i];
            }
            workspace.excessLogGamma[k] = logGamma;
        }
        double scale = GAS_CONSTANT / HuronVidalExcess.LAMBDA;
        double q = linear - scale * temperatureKelvin * excessEnergy;
        workspace.excessTemperature = temperatureKelvin;
        workspace.excessCoVolume = bMix;
        workspace.excessEnergyDt = excessEnergyDt;
        workspace.excessQ = q;
        workspace.excessQDt = linearDt - scale * (excessEnergy + temperatureKelvin * excessEnergyDt);
        for (int i = 0; i < count; i++) {
            double partial = workspace.a[i] / coVolumes[i] - scale * temperatureKelvin * workspace.excessLogGamma[i];
            workspace.excessPartialQ[i] = partial;
            workspace.sumA[i] = 0.5 * (coVolumes[i] * q + bMix * partial);
        }
    }

    /**
     * {@code d^2 a_mix/dT^2 = b Q''} of {@link Mixing#HURON_VIDAL} for the composition {@link #mixExcess} last formed:
     * {@code theta_i'' = (C_i'' - 2 theta_i' D_i' - theta_i D_i'')/D_i} with {@code D_i'' = sum_j w_j G_ji''},
     * {@code C_i'' = sum_j w_j (G_ji'' tau_ji + 2 G_ji' tau_ji' + G_ji tau_ji'')}, and
     * {@code Q'' = sum_i x_i a_i''/b_i - (R/Lambda)(2 E' + T E'')}.
     */
    private double excessSecondTemperatureDerivative(Workspace workspace) {
        double[] x = workspace.composition;
        double[] w = workspace.excessWeight;
        double excessEnergyDt2 = 0.0;
        double linearDt2 = 0.0;
        for (int i = 0; i < count; i++) {
            int row = i * count;
            double denominatorDt2 = 0.0;
            double numeratorDt2 = 0.0;
            for (int j = 0; j < count; j++) {
                int at = row + j;
                double weight = w[j];
                denominatorDt2 += weight * workspace.weightGDt2[at];
                numeratorDt2 += weight * (workspace.weightGDt2[at] * workspace.tau[at]
                        + 2.0 * workspace.weightGDt[at] * workspace.tauDt[at]
                        + workspace.weightG[at] * workspace.tauDt2[at]);
            }
            double thetaDt2 = (numeratorDt2 - 2.0 * workspace.excessThetaDt[i] * workspace.excessDenominatorDt[i]
                    - workspace.excessTheta[i] * denominatorDt2) / workspace.excessDenominator[i];
            excessEnergyDt2 += x[i] * thetaDt2;
            linearDt2 += x[i] * workspace.d2aDt2[i] / coVolumes[i];
        }
        double scale = GAS_CONSTANT / HuronVidalExcess.LAMBDA;
        double qDt2 = linearDt2 - scale * (2.0 * workspace.excessEnergyDt + workspace.excessTemperature * excessEnergyDt2);
        return workspace.excessCoVolume * qDt2;
    }

    /**
     * The derivative terms of {@link Mixing#HURON_VIDAL} that {@link #evaluateDerivatives} reads, for the composition
     * {@link #mixExcess} last formed, with {@code eps_ki = tau_ki - theta_i}:
     * <ul>
     *   <li>{@code ln gamma_k' = theta_k' + sum_i x_i (M_ki' eps_ki + M_ki eps_ki')},
     *   {@code M_ki' = b_k (G_ki' - G_ki D_i'/D_i)/D_i}, {@code eps_ki' = tau_ki' - theta_i'};
     *   {@code Qbar_i' = a_i'/b_i - r (ln gamma_i + T ln gamma_i')} and the row derivatives
     *   {@code sumA_i' = (b_i Q' + b Qbar_i')/2};</li>
     *   <li>{@code N_kl = n d ln gamma_k/dn_l = M_lk eps_lk + M_kl eps_kl - sum_i x_i M_ki M_li (eps_ki + eps_li)}
     *   (symmetric, {@code sum_k x_k N_kl = 0}) and the cross terms
     *   {@code crossA_kl = (1/2) d^2(n^2 a)/dn_k dn_l = (b_k Qbar_l + b_l Qbar_k - b r T N_kl)/2}.</li>
     * </ul>
     */
    private void differentiateExcess(double temperatureKelvin, Workspace workspace) {
        double[] x = workspace.composition;
        double[] m = workspace.excessLocalWeight;
        double[] eps = workspace.excessDeviation;
        for (int k = 0; k < count; k++) {
            double logGammaDt = workspace.excessThetaDt[k];
            for (int i = 0; i < count; i++) {
                int at = i * count + k;
                double denominator = workspace.excessDenominator[i];
                double local = coVolumes[k] * workspace.weightG[at] / denominator;
                double localDt = coVolumes[k] * (workspace.weightGDt[at]
                        - workspace.weightG[at] * workspace.excessDenominatorDt[i] / denominator) / denominator;
                double deviation = workspace.tau[at] - workspace.excessTheta[i];
                double deviationDt = workspace.tauDt[at] - workspace.excessThetaDt[i];
                logGammaDt += x[i] * (localDt * deviation + local * deviationDt);
                m[at] = local;
                eps[at] = deviation;
            }
            workspace.excessLogGammaDt[k] = logGammaDt;
        }
        double scale = GAS_CONSTANT / HuronVidalExcess.LAMBDA;
        double bMix = workspace.excessCoVolume;
        for (int i = 0; i < count; i++) {
            double partialDt = workspace.daDt[i] / coVolumes[i]
                    - scale * (workspace.excessLogGamma[i] + temperatureKelvin * workspace.excessLogGammaDt[i]);
            workspace.excessRowDt[i] = 0.5 * (coVolumes[i] * workspace.excessQDt + bMix * partialDt);
        }
        double crossScale = bMix * scale * temperatureKelvin;
        for (int k = 0; k < count; k++) {
            for (int l = k; l < count; l++) {
                double curvature = m[k * count + l] * eps[k * count + l] + m[l * count + k] * eps[l * count + k];
                double shared = 0.0;
                for (int i = 0; i < count; i++) {
                    int ik = i * count + k;
                    int il = i * count + l;
                    shared += x[i] * m[ik] * m[il] * (eps[ik] + eps[il]);
                }
                curvature -= shared;
                double cross = 0.5 * (coVolumes[k] * workspace.excessPartialQ[l] + coVolumes[l] * workspace.excessPartialQ[k]
                        - crossScale * curvature);
                workspace.excessCross[k * count + l] = cross;
                workspace.excessCross[l * count + k] = cross;
            }
        }
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

    private static void normalizeInto(double[] source, double[] target) {
        if (source == null || source.length != target.length) throw new IllegalArgumentException("Invalid composition length");
        double total = 0.0;
        for (double value : source) {
            if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("Invalid composition");
            total += value;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("Composition has no material");
        for (int i = 0; i < target.length; i++) target[i] = source[i] / total;
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
        /** Per pair of a {@link PairInteractions} model, filled by {@link #prepareTemperature}; empty otherwise. */
        private final double[] pairK;
        private final double[] pairKDt;
        private final double[] pairKDt2;
        /** {@code d sqrt(a_i)/dT} and its derivative for the interaction model's group pairs and the classical pairs of {@link Mixing#HURON_VIDAL}. */
        private final double[] interactionRootDt;
        private final double[] interactionRootDt2;
        /** {@code d^2a_i/dT^2}, filled by {@link #prepareTemperature} for a Mathias-Copeman compound and for {@link Mixing#HURON_VIDAL}. */
        private final double[] d2aDt2;
        /**
         * {@link Mixing#HURON_VIDAL}'s prepared pair terms at {@code [i * n + j]} (energy of {@code j} around {@code i}):
         * {@code tau_ji}, {@code G_ji} and their two temperature derivatives; empty for the quadratic plans.
         */
        private final double[] tau;
        private final double[] tauDt;
        private final double[] tauDt2;
        private final double[] weightG;
        private final double[] weightGDt;
        private final double[] weightGDt2;
        /** Composition terms of {@link Mixing#HURON_VIDAL} (see {@link PengRobinsonKernel#mixExcess}); empty otherwise. */
        private final double[] excessWeight;
        private final double[] excessDenominator;
        private final double[] excessDenominatorDt;
        private final double[] excessTheta;
        private final double[] excessThetaDt;
        private final double[] excessLogGamma;
        private final double[] excessLogGammaDt;
        private final double[] excessPartialQ;
        private final double[] excessRowDt;
        private final double[] excessLocalWeight;
        private final double[] excessDeviation;
        private final double[] excessCross;
        private double excessTemperature;
        private double excessCoVolume;
        private double excessEnergyDt;
        private double excessQ;
        private double excessQDt;
        private long preparedTemperature = Long.MIN_VALUE;

        private Workspace(int count) { this(count, 0, 0); }

        private Workspace(int count, int pairs, int excessCount) {
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
            interactionRootDt = new double[count];
            interactionRootDt2 = new double[count];
            d2aDt2 = new double[count];
            int cells = excessCount * excessCount;
            tau = new double[cells];
            tauDt = new double[cells];
            tauDt2 = new double[cells];
            weightG = new double[cells];
            weightGDt = new double[cells];
            weightGDt2 = new double[cells];
            excessWeight = new double[excessCount];
            excessDenominator = new double[excessCount];
            excessDenominatorDt = new double[excessCount];
            excessTheta = new double[excessCount];
            excessThetaDt = new double[excessCount];
            excessLogGamma = new double[excessCount];
            excessLogGammaDt = new double[excessCount];
            excessPartialQ = new double[excessCount];
            excessRowDt = new double[excessCount];
            excessLocalWeight = new double[cells];
            excessDeviation = new double[cells];
            excessCross = new double[cells];
        }

        private double interaction(int pair) { return pair < 0 ? 0.0 : pairK[pair]; }

        /** The normalized composition of the last evaluation. The caller must not mutate it. */
        public double[] compositionView() { return composition; }
        /** {@code sqrt(a_i)} of the prepared temperature; zero exactly where the PR alpha term vanishes. */
        public double[] attractionRootsView() { return sqrtA; }
        /**
         * {@code S_i = (1/2n) d(n^2 a)/dn_i} of the last evaluation: {@code sum_j x_j a_ij} for the quadratic plans,
         * {@code (b_i Q + b Qbar_i)/2} for {@link Mixing#HURON_VIDAL}. The caller must not mutate it.
         */
        public double[] attractionRowsView() { return sumA; }
        public void clear() {
            preparedTemperature = Long.MIN_VALUE;
            Arrays.fill(composition, 0.0);
        }
    }

    /** Caller-owned mutable output; public V3 results defensively copy the fugacity coefficients. */
    public static final class Evaluation {
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
    public static final class Derivatives {
        private final Evaluation evaluation;
        private final double[] dLogPhiDt;
        private final double[][] dLogPhiDn;
        private final double[] partialMolarResidualEnthalpy;
        private final double[] rootDt;
        private final double[] rootDt2;
        private final double[] crossDt;
        private final double[] coVolumeRatio;
        private final double[] attractionRatio;
        private double dResidualEnthalpyDt;

        private Derivatives(int count) {
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
        public double dResidualEnthalpyDt() { return dResidualEnthalpyDt; }
    }

    public record RootSelection(double selectedCompressibility, int physicalRootCount, double rootSeparation) {}
}

package com.wormzjl.icheme.science.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Arrays;

/**
 * Excess Gibbs energy model of the PR78 kernel's Huron-Vidal mixing rule
 * ({@link PengRobinsonKernel#huronVidal}), at infinite pressure for PR:
 *
 * <pre>
 *   a_mix / b_mix = sum_i x_i a_i/b_i - gE/Lambda,   b_mix = sum_i x_i b_i,
 *   Lambda = ln((2 + sqrt2)/(2 - sqrt2)) / (2 sqrt2)
 *   gE/(R T) = sum_i x_i [sum_j x_j b_j G_ji tau_ji] / [sum_k x_k b_k G_ki]
 *   tau_ji = dg_ji/(R T),   G_ji = exp(-alpha_ji tau_ji),   alpha_ji = alpha_ij,   dg_ii = 0
 * </pre>
 *
 * <p>(NRTL with local compositions weighted by the co-volumes). Each unordered pair is one of two kinds:</p>
 * <ul>
 *   <li>an NRTL pair: {@code alpha_ij} in {@code [0, 1)} and the two energies {@code dg_ij}, {@code dg_ji} (J/mol),
 *   each linear in temperature through its values at two temperatures;</li>
 *   <li>a classical pair: {@code alpha_ij = 0} and {@code dg_ji(T) = g_ji - g_ii}, {@code g_ii = -Lambda a_i(T)/b_i},
 *   {@code g_ji = -2 sqrt(b_i b_j)/(b_i + b_j) sqrt(g_ii g_jj)(1 - k_ij(T))}, which the kernel evaluates on its own
 *   {@code a_i(T)} and {@code b_i}. Its {@code k_ij(T)} is a constant, a line {@code k0 + k1 T} through its values at
 *   two temperatures, or a pair of a {@link PairInteractions} model over the same basis (E-PPR78's {@code k_ij(T)},
 *   evaluated by the kernel with its two temperature derivatives). With every pair classical the rule is exactly the
 *   quadratic rule {@code a_ij = sqrt(a_i a_j)(1 - k_ij(T))}.</li>
 * </ul>
 *
 * <p>A pair not declared is classical with {@code k_ij = 0}. The object is immutable; the kernel copies its values into
 * its own per-pair arrays at construction.</p>
 */
public final class HuronVidalExcess {
    private static final double SQRT_TWO = Math.sqrt(2.0);
    /** {@code ln((2 + sqrt2)/(2 - sqrt2)) / (2 sqrt2) = 0.6232252...}, PR's infinite-pressure limit. */
    public static final double LAMBDA = DeterministicMath.log((2.0 + SQRT_TWO) / (2.0 - SQRT_TWO)) / (2.0 * SQRT_TWO);

    private final int componentCount;
    /** Row-major {@code [i * n + j]}, symmetric: true for a classical pair (and for the diagonal). */
    private final boolean[] classical;
    /** Symmetric; zero for a classical pair. */
    private final double[] alpha;
    /** Symmetric {@code k0} of a classical pair's {@code k_ij(T) = k0 + k1 T}; zero for an NRTL pair and a model pair. */
    private final double[] interaction;
    /** Symmetric {@code k1}; zero for a constant pair, an NRTL pair and a model pair. */
    private final double[] interactionSlope;
    /** The model of the classical pairs whose {@code k_ij(T)} it gives; null when there are none. */
    private final PairInteractions interactions;
    /** Symmetric: the pair of {@link #interactions} that gives {@code (i, j)}, or -1. */
    private final int[] modelPair;
    /** {@code dg_ji(T) = intercept + slope T} stored at {@code [i * n + j]} (energy of {@code j} around a centre {@code i}). */
    private final double[] energyIntercept;
    private final double[] energySlope;
    private final int nrtlPairs;
    private final boolean rankOne;

    private HuronVidalExcess(int componentCount, boolean[] classical, double[] alpha, double[] interaction,
                             double[] interactionSlope, PairInteractions interactions, int[] modelPair,
                             double[] energyIntercept, double[] energySlope) {
        this.componentCount = componentCount;
        this.classical = classical;
        this.alpha = alpha;
        this.interaction = interaction;
        this.interactionSlope = interactionSlope;
        this.interactions = interactions;
        this.modelPair = modelPair;
        this.energyIntercept = energyIntercept;
        this.energySlope = energySlope;
        int pairs = 0;
        boolean zero = true;
        for (int i = 0; i < componentCount; i++) {
            for (int j = i + 1; j < componentCount; j++) {
                int at = i * componentCount + j;
                if (!classical[at]) pairs++;
                zero &= classical[at] && interaction[at] == 0.0 && interactionSlope[at] == 0.0 && modelPair[at] < 0;
            }
        }
        this.nrtlPairs = pairs;
        this.rankOne = zero;
    }

    public static Builder builder(int componentCount) {
        return new Builder(componentCount);
    }

    public int componentCount() { return componentCount; }
    /** Unordered pairs declared with NRTL energies. */
    public int nrtlPairCount() { return nrtlPairs; }
    /** True when every pair is classical with {@code k_ij = 0} at every temperature: the rule is then the rank-one product. */
    public boolean rankOne() { return rankOne; }
    /** True for a classical pair, declared or not; the diagonal counts as classical. */
    public boolean classical(int i, int j) { return classical[index(i, j)]; }
    public double alpha(int i, int j) { return alpha[index(i, j)]; }
    /**
     * {@code k0} of a classical pair's {@code k_ij(T) = k0 + k1 T}: its {@code k_ij} when constant; zero for an NRTL pair
     * and for a pair of {@link #interactions()}.
     */
    public double interaction(int i, int j) { return interaction[index(i, j)]; }
    /** {@code k1 = dk_ij/dT} of a classical pair's line; zero for a constant, an NRTL and a model pair. */
    public double interactionSlope(int i, int j) { return interactionSlope[index(i, j)]; }
    /** The model that gives the {@code k_ij(T)} of {@link #interactionPair} pairs; null when no pair is declared from one. */
    public PairInteractions interactions() { return interactions; }
    /** The pair of {@link #interactions()} that gives {@code (i, j)}, or -1. */
    public int interactionPair(int i, int j) { return modelPair[index(i, j)]; }
    /** {@code dg_ji} at zero kelvin of the line {@code dg_ji(T) = intercept + slope T}; zero for a classical pair. */
    public double energyIntercept(int j, int i) { return energyIntercept[index(i, j)]; }
    /** {@code d(dg_ji)/dT}; zero for a classical pair. */
    public double energySlope(int j, int i) { return energySlope[index(i, j)]; }

    private int index(int i, int j) {
        if (i < 0 || j < 0 || i >= componentCount || j >= componentCount) throw new IndexOutOfBoundsException("Pair index out of range");
        return i * componentCount + j;
    }

    /** Declares pairs one at a time; each unordered pair at most once. */
    public static final class Builder {
        private final int componentCount;
        private final boolean[] declared;
        private final boolean[] classical;
        private final double[] alpha;
        private final double[] interaction;
        private final double[] interactionSlope;
        private final int[] modelPair;
        private final double[] energyIntercept;
        private final double[] energySlope;
        private PairInteractions interactions;

        private Builder(int componentCount) {
            if (componentCount <= 0) throw new IllegalArgumentException("At least one component is required");
            this.componentCount = componentCount;
            int cells = componentCount * componentCount;
            this.declared = new boolean[cells];
            this.classical = new boolean[cells];
            Arrays.fill(classical, true);
            this.alpha = new double[cells];
            this.interaction = new double[cells];
            this.interactionSlope = new double[cells];
            this.modelPair = new int[cells];
            Arrays.fill(modelPair, -1);
            this.energyIntercept = new double[cells];
            this.energySlope = new double[cells];
        }

        /**
         * An NRTL pair: {@code alpha_ij} and the energies {@code dg_ij} and {@code dg_ji} (J/mol) at two temperatures,
         * linear in temperature through both (and beyond them).
         */
        public Builder nrtl(int i, int j, double alphaIj,
                            double firstKelvin, double dgIjAtFirst, double dgJiAtFirst,
                            double secondKelvin, double dgIjAtSecond, double dgJiAtSecond) {
            if (!Double.isFinite(alphaIj) || alphaIj < 0.0 || alphaIj >= 1.0) {
                throw new IllegalArgumentException("NRTL alpha must lie in [0, 1)");
            }
            requireTemperatures(firstKelvin, secondKelvin, "NRTL energies");
            if (!Double.isFinite(dgIjAtFirst) || !Double.isFinite(dgJiAtFirst) || !Double.isFinite(dgIjAtSecond)
                    || !Double.isFinite(dgJiAtSecond)) {
                throw new IllegalArgumentException("NRTL energies must be finite");
            }
            claim(i, j);
            double slopeIj = (dgIjAtSecond - dgIjAtFirst) / (secondKelvin - firstKelvin);
            double slopeJi = (dgJiAtSecond - dgJiAtFirst) / (secondKelvin - firstKelvin);
            double interceptIj = dgIjAtFirst - slopeIj * firstKelvin;
            double interceptJi = dgJiAtFirst - slopeJi * firstKelvin;
            if (!Double.isFinite(slopeIj) || !Double.isFinite(slopeJi) || !Double.isFinite(interceptIj)
                    || !Double.isFinite(interceptJi)) {
                throw new IllegalArgumentException("NRTL energy line is not finite");
            }
            int ij = i * componentCount + j;
            int ji = j * componentCount + i;
            classical[ij] = false;
            classical[ji] = false;
            alpha[ij] = alphaIj;
            alpha[ji] = alphaIj;
            // dg_ij is the energy of i around a centre j: stored at [j * n + i]; dg_ji at [i * n + j].
            energyIntercept[ji] = interceptIj;
            energySlope[ji] = slopeIj;
            energyIntercept[ij] = interceptJi;
            energySlope[ij] = slopeJi;
            return this;
        }

        /** A classical pair with a constant {@code k_ij}; zero is accepted and is the undeclared default. */
        public Builder classical(int i, int j, double kij) {
            if (!Double.isFinite(kij)) throw new IllegalArgumentException("Classical interaction must be finite");
            claim(i, j);
            interaction[i * componentCount + j] = kij;
            interaction[j * componentCount + i] = kij;
            return this;
        }

        /**
         * A classical pair whose {@code k_ij} is linear in temperature through its values at two temperatures (and
         * beyond them): {@code k_ij(T) = k0 + k1 T}.
         */
        public Builder classicalLinear(int i, int j, double firstKelvin, double kAtFirst, double secondKelvin, double kAtSecond) {
            requireTemperatures(firstKelvin, secondKelvin, "A linear classical interaction");
            if (!Double.isFinite(kAtFirst) || !Double.isFinite(kAtSecond)) throw new IllegalArgumentException("Classical interaction must be finite");
            double slope = (kAtSecond - kAtFirst) / (secondKelvin - firstKelvin);
            double intercept = kAtFirst - slope * firstKelvin;
            if (!Double.isFinite(slope) || !Double.isFinite(intercept)) throw new IllegalArgumentException("Classical interaction line is not finite");
            claim(i, j);
            interaction[i * componentCount + j] = intercept;
            interaction[j * componentCount + i] = intercept;
            interactionSlope[i * componentCount + j] = slope;
            interactionSlope[j * componentCount + i] = slope;
            return this;
        }

        /**
         * Every pair of {@code model} (over the same basis) as a classical pair whose {@code k_ij(T)} the kernel takes from
         * the model at each temperature, with its first and second temperature derivatives. At most one model.
         */
        public Builder interactions(PairInteractions model) {
            if (model == null) throw new IllegalArgumentException("Interaction model is required");
            if (interactions != null) throw new IllegalArgumentException("At most one interaction model");
            if (model.componentCount() != componentCount) throw new IllegalArgumentException("Interaction model basis does not match the components");
            for (int pair = 0; pair < model.pairCount(); pair++) {
                int i = model.first(pair);
                int j = model.second(pair);
                claim(i, j);
                modelPair[i * componentCount + j] = pair;
                modelPair[j * componentCount + i] = pair;
            }
            interactions = model;
            return this;
        }

        public HuronVidalExcess build() {
            return new HuronVidalExcess(componentCount, classical.clone(), alpha.clone(), interaction.clone(),
                    interactionSlope.clone(), interactions, modelPair.clone(), energyIntercept.clone(), energySlope.clone());
        }

        private static void requireTemperatures(double firstKelvin, double secondKelvin, String what) {
            if (!Double.isFinite(firstKelvin) || !Double.isFinite(secondKelvin) || !(firstKelvin > 0.0)
                    || !(secondKelvin > 0.0) || firstKelvin == secondKelvin) {
                throw new IllegalArgumentException(what + " need two distinct positive finite temperatures");
            }
        }

        private void claim(int i, int j) {
            if (i < 0 || j < 0 || i >= componentCount || j >= componentCount) throw new IllegalArgumentException("Pair index out of range");
            if (i == j) throw new IllegalArgumentException("A component has no interaction with itself");
            int at = Math.min(i, j) * componentCount + Math.max(i, j);
            if (declared[at]) throw new IllegalArgumentException("Pair (" + Math.min(i, j) + ", " + Math.max(i, j) + ") is declared twice");
            declared[at] = true;
        }
    }

    @Override public String toString() {
        return "HuronVidalExcess[components=" + componentCount + ", nrtlPairs=" + nrtlPairs
                + (interactions == null ? "" : ", modelPairs=" + interactions.pairCount()) + "]";
    }
}
